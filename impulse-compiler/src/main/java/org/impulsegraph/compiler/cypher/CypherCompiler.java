package org.impulsegraph.compiler.cypher;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.cel.CelParser;
import org.impulsegraph.compiler.impscm.v11.ImpScmV11Compiler;
import org.impulsegraph.compiler.impscm.v11.V11ExprLowerer;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.vm.CompiledQuery;

import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lowers parsed Cypher AST queries into optimized ImpScheme S-Expression AST
 * (ScmProgram) and ImpulseVM bytecode, supporting single-lane and 64-lane
 * multi-seed (`IN [...]`), unbounded Kleene star (`[:REL*]`), undirected/union
 * hops (`-[:R1|R2]-`), quorum filtering (`0xC6`), and hybrid vector ANN/rerank
 * (`0xCA`/`0xCB`).
 */
public final class CypherCompiler {

	public static final int MAX_UNROLLED_PATH_HOPS = 64;

	public record CompilationResult(ScmProgram ast, String seedVariable, String seedParameterOrValue) {
	}

	private CypherCompiler() {
	}

	/**
	 * Parse openCypher text and compile into ImpScheme AST.
	 */
	public static CompilationResult compile(String cypherQuery) {
		Objects.requireNonNull(cypherQuery, "cypherQuery must not be null");
		CypherParser.CypherQuery parsed = CypherParser.parse(cypherQuery);
		return compile(parsed);
	}

	/**
	 * Parse openCypher text and compile directly to an executable CompiledQuery.
	 */
	public static CompiledQuery compileToExecutable(String cypherQuery, ImpulseGraphSnapshot snapshot, Arena arena) {
		CompilationResult res = compile(cypherQuery);
		return CompiledQuery.fromV11(res.ast(), snapshot);
	}

	/**
	 * Lowers an openCypher query into canonical ImpScheme v0.11 S-expression IR
	 * with full {@code DECIMAL(P, S)} support, strict coercion semantics, and
	 * schema-guided parameter type inference.
	 */
	public static String lowerToImpSchemeV11(String cypherQuery, ImpulseGraphSnapshot snapshot) {
		return lowerToImpSchemeV11(cypherQuery, snapshot, Map.of());
	}

	/**
	 * Lowers an openCypher query into canonical ImpScheme v0.11 S-expression IR
	 * using optional explicit parameter type declarations (e.g.
	 * {@code "$rate" -> "DECIMAL(10, 4)"} or {@code "(decimal 10 4)"}).
	 */
	public static String lowerToImpSchemeV11(String cypherQuery, ImpulseGraphSnapshot snapshot,
			Map<String, String> paramTypes) {
		Objects.requireNonNull(cypherQuery, "cypherQuery must not be null");
		CypherParser.CypherQuery parsed = CypherParser.parse(cypherQuery);
		return lowerToImpSchemeV11(parsed, snapshot, paramTypes != null ? paramTypes : Map.of());
	}

	/**
	 * Compiles an openCypher query directly to validated v0.11 bytecode via
	 * {@link ImpScmV11Compiler}.
	 */
	public static ImpScmV11Compiler.CompileResult compileToV11(String cypherQuery, ImpulseGraphSnapshot snapshot) {
		return compileToV11(cypherQuery, snapshot, Map.of());
	}

	/**
	 * Compiles an openCypher query with explicit parameter types directly to
	 * validated v0.11 bytecode via {@link ImpScmV11Compiler}.
	 */
	public static ImpScmV11Compiler.CompileResult compileToV11(String cypherQuery, ImpulseGraphSnapshot snapshot,
			Map<String, String> paramTypes) {
		String scm = lowerToImpSchemeV11(cypherQuery, snapshot, paramTypes);
		return ImpScmV11Compiler.compile(scm, snapshot, true);
	}

	public static String lowerToImpSchemeV11(CypherParser.CypherQuery query, ImpulseGraphSnapshot snapshot,
			Map<String, String> explicitParamTypes) {
		Objects.requireNonNull(query, "query must not be null");
		String startVar = query.path().startNode().variable() != null ? query.path().startNode().variable() : "n";
		String startDom = resolveDomainName(query.path().startNode().label(), snapshot);
		CypherParser.PathStep firstStep = query.path().steps().isEmpty() ? null : query.path().steps().get(0);
		String edgeVar = firstStep != null ? firstStep.edge().variable() : null;
		String relName = firstStep != null ? firstStep.edge().relationName() : null;
		String tgtVar = firstStep != null && firstStep.targetNode().variable() != null
				? firstStep.targetNode().variable()
				: null;
		String tgtDom = firstStep != null ? resolveDomainName(firstStep.targetNode().label(), snapshot) : startDom;

		// Normalize explicit param types ($name -> canonical v11 type)
		Map<String, String> paramDecls = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : explicitParamTypes.entrySet()) {
			String pname = e.getKey().startsWith("$") ? e.getKey() : "$" + e.getKey();
			paramDecls.put(pname, V11ExprLowerer.normalizeParamType(e.getValue()));
		}

		// Partition WHERE predicates into: seed ID, start-node filters, edge filters
		List<CypherParser.WherePredicate> startPreds = new ArrayList<>();
		List<CypherParser.WherePredicate> edgePreds = new ArrayList<>();
		String seedIdExpr = null;

		for (CypherParser.WherePredicate pred : query.wherePredicates()) {
			if (pred.lhsExpr() == null && startVar.equals(pred.targetVar()) && "id".equalsIgnoreCase(pred.field())
					&& ("=".equals(pred.op()) || "==".equals(pred.op()))) {
				seedIdExpr = pred.valueOrParam();
				if (seedIdExpr.startsWith("$") && !paramDecls.containsKey(seedIdExpr)) {
					paramDecls.put(seedIdExpr, "(id \"" + startDom + "\")");
				}
				continue;
			}
			// Infer $param type from DECIMAL64 column metadata when not explicitly declared
			inferParamTypeFromPredicate(pred, startVar, startDom, edgeVar, relName, snapshot, paramDecls);

			boolean isEdgePred = edgeVar != null && (edgeVar.equals(pred.targetVar())
					|| (pred.lhsExpr() != null && pred.lhsExpr().contains(edgeVar + ".")));
			if (isEdgePred) {
				edgePreds.add(pred);
			} else {
				startPreds.add(pred);
			}
		}

		// Collect all node attributes referenced in WITH and RETURN items
		Map<String, String> loadedNodeAttrs = new LinkedHashMap<>(); // "var.attr" -> letBindingName
		Set<String> withAliases = new LinkedHashSet<>();
		for (CypherParser.WithItem wi : query.withItems()) {
			withAliases.add(wi.alias());
		}

		List<String> letBindings = new ArrayList<>();
		String curFrontier = "f0";
		boolean filtered = false;

		if (seedIdExpr != null) {
			letBindings.add("[f0 (seed \"" + startDom + "\" " + seedIdExpr + ")]");
			filtered = true;
		} else {
			letBindings.add("[f0 (domain-all \"" + startDom + "\")]");
		}

		// Apply start-node WHERE predicates via node-filter
		int filterIdx = 0;
		for (CypherParser.WherePredicate pred : startPreds) {
			String nextF = "f" + (++filterIdx);
			String lhsRaw = pred.lhsExpr() != null ? pred.lhsExpr() : (pred.targetVar() + "." + pred.field());
			String fullCmp = lhsRaw + " " + pred.op() + " " + pred.valueOrParam();
			String loweredCmp = V11ExprLowerer.lowerExpr(fullCmp, id -> id, (v, m) -> "(attr " + v + " \"" + m + "\")");
			letBindings.add(
					"[" + nextF + " (node-filter " + curFrontier + " (lambda (" + startVar + ") " + loweredCmp + "))]");
			curFrontier = nextF;
			filtered = true;
		}

		// Helper to resolve node attribute access in top-level WITH/RETURN expressions
		java.util.function.BiFunction<String, String, String> topLevelMemberResolver = (v, m) -> {
			String key = v + "." + m;
			return loadedNodeAttrs.computeIfAbsent(key, k -> {
				String dom = (tgtVar != null && tgtVar.equals(v)) ? tgtDom : startDom;
				String bindName = "__attr_" + v + "_" + m;
				letBindings.add("[" + bindName + " (load-node-attr \"" + dom + "\" \"" + m + "\")]");
				return bindName;
			});
		};

		// Lower WITH items into let bindings
		for (CypherParser.WithItem wi : query.withItems()) {
			String lowered = V11ExprLowerer.lowerExpr(wi.expr(), id -> id, topLevelMemberResolver);
			letBindings.add("[" + wi.alias() + " " + lowered + "]");
		}

		// Lower RETURN items
		List<CypherParser.ReturnItem> retItems = query.returnItems();
		if (retItems.isEmpty()) {
			retItems = List.of(new CypherParser.ReturnItem(query.projection().variable(), null,
					query.projection().isCount() ? "COUNT" : null, query.projection().variable(), null));
		}

		List<String> collects = new ArrayList<>();
		String walkedFrontier = null;
		int colIdx = 0;

		for (CypherParser.ReturnItem item : retItems) {
			String outName = item.alias() != null
					? item.alias()
					: (item.aggregateFn() != null
							? item.aggregateFn().toLowerCase() + "_" + colIdx
							: (!item.targetVar().isEmpty() ? item.targetVar() : "col_" + colIdx));
			colIdx++;

			boolean referencesEdge = edgeVar != null && (edgeVar.equals(item.targetVar())
					|| (item.expr() != null && item.expr().contains(edgeVar + ".")));

			if (referencesEdge && "SUM".equalsIgnoreCase(item.aggregateFn()) && firstStep != null) {
				// Roll up edge expression via edge-stream + scatter-add + reduce :sum
				String dir = firstStep.edge().isForward() ? ":fwd" : ":rev";
				int accScale = inferEdgeExprScale(item.expr(), edgeVar, relName, snapshot);
				String accName = "__acc_" + colIdx;
				String rolledName = "__rolled_" + colIdx;
				letBindings.add("[" + accName + " (vec-fill \"" + tgtDom + "\" (decimal 18 " + accScale
						+ ") (dec \"0\" 18 " + accScale + "))]");
				StringBuilder lam = new StringBuilder("(lambda (").append(startVar).append(' ')
						.append(tgtVar != null ? tgtVar : "__tgt").append(' ').append(edgeVar).append(") ");
				if (!edgePreds.isEmpty()) {
					String whereExpr = lowerEdgePredicates(edgePreds, edgeVar, relName, snapshot, letBindings);
					lam.append("(where ").append(whereExpr).append(") ");
				}
				String loweredEdgeVal = V11ExprLowerer.lowerExpr(item.expr(), id -> id,
						(v, m) -> v.equals(edgeVar)
								? "(edge-attr " + v + " \"" + m + "\")"
								: "(attr " + v + " \"" + m + "\")");
				lam.append("(scatter-add ").append(accName).append(' ').append(tgtVar != null ? tgtVar : "__tgt")
						.append(' ').append(loweredEdgeVal).append("))");
				letBindings.add("[" + rolledName + " (edge-stream " + dir + " \"" + relName + "\" " + curFrontier + " "
						+ lam + ")]");
				collects.add("(collect :scalar \"" + outName + "\" (reduce :sum " + rolledName + "))");
				continue;
			}

			if ("COUNT".equalsIgnoreCase(item.aggregateFn())) {
				String targetF = curFrontier;
				if (firstStep != null && tgtVar != null && tgtVar.equals(item.targetVar())) {
					if (walkedFrontier == null) {
						walkedFrontier = emitEdgeWalkOrStream(firstStep, startVar, tgtVar, edgeVar, tgtDom, curFrontier,
								edgePreds, snapshot, letBindings);
					}
					targetF = walkedFrontier;
				}
				collects.add("(collect :scalar \"" + outName + "\" (cardinality " + targetF + "))");
				continue;
			}

			if (item.aggregateFn() != null) {
				String reduceOp = switch (item.aggregateFn().toUpperCase()) {
					case "SUM" -> ":sum";
					case "MIN" -> ":min";
					case "MAX" -> ":max";
					case "AVG" -> ":avg";
					default -> throw new IllegalArgumentException("Unsupported aggregate: " + item.aggregateFn());
				};
				String loweredExpr = V11ExprLowerer.lowerExpr(item.expr(), id -> id, topLevelMemberResolver);
				String maskPart = filtered ? " :mask " + curFrontier : "";
				collects.add("(collect :scalar \"" + outName + "\" (reduce " + reduceOp + " " + loweredExpr + maskPart
						+ "))");
				continue;
			}

			// Plain node variable return (e.g. RETURN a or RETURN b)
			if (item.field() == null && !item.targetVar().isEmpty() && !withAliases.contains(item.targetVar())) {
				if (firstStep != null && tgtVar != null && tgtVar.equals(item.targetVar())) {
					if (walkedFrontier == null) {
						walkedFrontier = emitEdgeWalkOrStream(firstStep, startVar, tgtVar, edgeVar, tgtDom, curFrontier,
								edgePreds, snapshot, letBindings);
					}
					collects.add("(collect :frontier-ids \"" + outName + "\" " + walkedFrontier + ")");
				} else {
					collects.add("(collect :frontier-ids \"" + outName + "\" " + curFrontier + ")");
				}
				continue;
			}

			// Vector or scalar expression return
			int beforeAttrs = loadedNodeAttrs.size();
			String loweredExpr = V11ExprLowerer.lowerExpr(item.expr(), id -> id, topLevelMemberResolver);
			boolean isVec = loadedNodeAttrs.size() > beforeAttrs || referencesAny(item.expr(), loadedNodeAttrs.keySet())
					|| referencesAny(item.expr(), withAliases);
			collects.add("(collect " + (isVec ? ":vec" : ":scalar") + " \"" + outName + "\" " + loweredExpr + ")");
		}

		// Assemble (query (param ...) (let ([...] ...) (collect ...)))
		StringBuilder scm = new StringBuilder("(query\n");
		for (Map.Entry<String, String> p : paramDecls.entrySet()) {
			scm.append("  (param ").append(p.getKey()).append(' ').append(p.getValue()).append(")\n");
		}
		scm.append("  (let (");
		for (int i = 0; i < letBindings.size(); i++) {
			if (i > 0) {
				scm.append("\n        ");
			}
			scm.append(letBindings.get(i));
		}
		scm.append(")\n");
		for (String col : collects) {
			scm.append("    ").append(col).append('\n');
		}
		scm.append("  ))");
		return scm.toString();
	}

	private static String emitEdgeWalkOrStream(CypherParser.PathStep step, String startVar, String tgtVar,
			String edgeVar, String tgtDom, String curFrontier, List<CypherParser.WherePredicate> edgePreds,
			ImpulseGraphSnapshot snapshot, List<String> letBindings) {
		String dir = step.edge().isForward() ? ":fwd" : ":rev";
		String relName = step.edge().relationName();
		if (edgePreds.isEmpty()) {
			letBindings.add("[__walked (walk " + dir + " \"" + relName + "\" " + curFrontier + ")]");
			return "__walked";
		}
		String eVar = edgeVar != null ? edgeVar : "__e";
		String tVar = tgtVar != null ? tgtVar : "__tgt";
		letBindings.add("[__out_f (seed \"" + tgtDom + "\")]");
		String whereExpr = lowerEdgePredicates(edgePreds, eVar, relName, snapshot, letBindings);
		letBindings.add(
				"[__walked (edge-stream " + dir + " \"" + relName + "\" " + curFrontier + " (lambda (" + startVar + " "
						+ tVar + " " + eVar + ") (where " + whereExpr + ") (yield-frontier __out_f " + tVar + ")))]");
		return "__walked";
	}

	private static String lowerEdgePredicates(List<CypherParser.WherePredicate> edgePreds, String edgeVar,
			String relName, ImpulseGraphSnapshot snapshot, List<String> letBindings) {
		List<String> parts = new ArrayList<>();
		int hoistIdx = 0;
		for (CypherParser.WherePredicate pred : edgePreds) {
			String lhsRaw = pred.lhsExpr() != null ? pred.lhsExpr() : (pred.targetVar() + "." + pred.field());
			String lhsScm = V11ExprLowerer.lowerExpr(lhsRaw, id -> id, (v,
					m) -> v.equals(edgeVar) ? "(edge-attr " + v + " \"" + m + "\")" : "(attr " + v + " \"" + m + "\")");
			String rhsScm = V11ExprLowerer.lowerExpr(pred.valueOrParam(), id -> id, (v,
					m) -> v.equals(edgeVar) ? "(edge-attr " + v + " \"" + m + "\")" : "(attr " + v + " \"" + m + "\")");
			if (!rhsScm.contains("(edge-attr ") && !rhsScm.contains("(attr ") && !rhsScm.startsWith("$")) {
				String hoistVar = "__ek" + (++hoistIdx);
				int[] decPS = pred.lhsExpr() == null ? lookupEdgeDecimalPS(snapshot, relName, pred.field()) : null;
				if (decPS != null && rhsScm.matches("-?\\d+")) {
					letBindings.add("[" + hoistVar + " (cast " + rhsScm + " (decimal 18 " + decPS[1] + "))]");
				} else {
					letBindings.add("[" + hoistVar + " " + rhsScm + "]");
				}
				rhsScm = hoistVar;
			}
			String scmOp = switch (pred.op()) {
				case "==" -> "=";
				case "<>" -> "!=";
				default -> pred.op();
			};
			parts.add("(" + scmOp + " " + lhsScm + " " + rhsScm + ")");
		}
		if (parts.size() == 1) {
			return parts.get(0);
		}
		String acc = parts.get(0);
		for (int i = 1; i < parts.size(); i++) {
			acc = "(and " + acc + " " + parts.get(i) + ")";
		}
		return acc;
	}

	private static void inferParamTypeFromPredicate(CypherParser.WherePredicate pred, String startVar, String startDom,
			String edgeVar, String relName, ImpulseGraphSnapshot snapshot, Map<String, String> paramDecls) {
		String rhs = pred.valueOrParam();
		if (rhs == null || !rhs.startsWith("$") || paramDecls.containsKey(rhs)) {
			return;
		}
		if (edgeVar != null && edgeVar.equals(pred.targetVar()) && relName != null) {
			int[] ps = lookupEdgeDecimalPS(snapshot, relName, pred.field());
			if (ps != null) {
				paramDecls.put(rhs, "(decimal " + ps[0] + " " + ps[1] + ")");
				return;
			}
		}
		if (startVar.equals(pred.targetVar())) {
			int[] ps = lookupNodeDecimalPS(snapshot, startDom, pred.field());
			if (ps != null) {
				paramDecls.put(rhs, "(decimal " + ps[0] + " " + ps[1] + ")");
			}
		}
	}

	private static int inferEdgeExprScale(String expr, String edgeVar, String relName, ImpulseGraphSnapshot snapshot) {
		if (snapshot != null && relName != null && expr != null) {
			RelationSnapshot rs = snapshot.getRelationSnapshot(relName);
			if (rs instanceof org.impulsegraph.storage.csr.RelationSnapshot csrRel) {
				for (BinarySnapshotLoader.LoadedAttribute la : csrRel.getLoadedAttributes()) {
					if ((la.typeCode() == 0x15 || la.typeCode() == (byte) 0x95)
							&& expr.contains(edgeVar + "." + la.name())) {
						return la.scale();
					}
				}
			}
		}
		return 2;
	}

	private static int[] lookupNodeDecimalPS(ImpulseGraphSnapshot snapshot, String domName, String attrName) {
		if (snapshot instanceof GraphSnapshot gs && domName != null && attrName != null) {
			BinarySnapshotLoader.LoadedDomain ld = gs.getDomain(domName);
			if (ld != null) {
				int idx = ld.getAttributeIndex(attrName);
				if (idx >= 0) {
					BinarySnapshotLoader.LoadedAttribute la = ld.getAttribute(idx);
					if (la.typeCode() == 0x15 || la.typeCode() == (byte) 0x95) {
						return new int[]{la.precision(), la.scale()};
					}
				}
			}
		}
		return null;
	}

	private static int[] lookupEdgeDecimalPS(ImpulseGraphSnapshot snapshot, String relName, String attrName) {
		if (snapshot != null && relName != null && attrName != null) {
			RelationSnapshot rs = snapshot.getRelationSnapshot(relName);
			if (rs instanceof org.impulsegraph.storage.csr.RelationSnapshot csrRel) {
				for (BinarySnapshotLoader.LoadedAttribute la : csrRel.getLoadedAttributes()) {
					if (la.name().equals(attrName) && (la.typeCode() == 0x15 || la.typeCode() == (byte) 0x95)) {
						return new int[]{la.precision(), la.scale()};
					}
				}
			}
		}
		return null;
	}

	private static String resolveDomainName(String label, ImpulseGraphSnapshot snapshot) {
		if (label != null && !label.isBlank()) {
			return label;
		}
		if (snapshot != null && snapshot.getDomainNames() != null && !snapshot.getDomainNames().isEmpty()) {
			return snapshot.getDomainNames().iterator().next();
		}
		return "Node";
	}

	private static boolean referencesAny(String expr, Set<String> tokens) {
		if (expr == null) {
			return false;
		}
		for (String tok : tokens) {
			if (expr.contains(tok)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Lowers a structured CypherQuery to ScmProgram.
	 */
	public static CompilationResult compile(CypherParser.CypherQuery query) {
		Objects.requireNonNull(query, "query must not be null");
		List<ImpScmNode> steps = new ArrayList<>();
		String seedVar = query.path().startNode().variable();
		String seedParamOrVal = null;
		List<Long> multiSeeds = List.of();
		CypherParser.WherePredicate vectorAnnPred = null;
		int quorumK = 0;

		// 1. Identify seed node constraints, IN [...], quorum, and vector_ann from
		// WHERE predicates
		for (CypherParser.WherePredicate pred : query.wherePredicates()) {
			if ("IN".equalsIgnoreCase(pred.op()) && !pred.inValues().isEmpty()
					&& (seedVar == null || pred.targetVar().equals(seedVar))) {
				multiSeeds = pred.inValues();
				seedParamOrVal = pred.valueOrParam();
			} else if ("ANN".equalsIgnoreCase(pred.op()) && "vector_ann".equalsIgnoreCase(pred.field())) {
				vectorAnnPred = pred;
			} else if ("quorum".equalsIgnoreCase(pred.field()) && pred.topK() > 0) {
				quorumK = pred.topK();
			} else if (seedVar != null && pred.targetVar().equals(seedVar)) {
				seedParamOrVal = pred.valueOrParam();
			}
		}

		boolean multiLaneMode = !multiSeeds.isEmpty() || (vectorAnnPred != null && quorumK > 0);

		if (!multiSeeds.isEmpty()) {
			steps.add(ScmLaneInitSeeds.fromInlineSeeds(multiSeeds));
		} else if (vectorAnnPred != null) {
			steps.add(ScmVectorAnnSearch.fromInlineVector(vectorAnnPred.vectorArg(), vectorAnnPred.topK(), 0,
					multiLaneMode));
		}

		// 2. Lower Path Steps
		for (CypherParser.PathStep step : query.path().steps()) {
			CypherParser.EdgePattern edge = step.edge();
			if (edge.minHops() < 0) {
				throw new IllegalArgumentException(
						"Variable-length path minHops cannot be negative: " + edge.minHops());
			}
			if (!edge.isUnbounded() && edge.maxHops() < edge.minHops()) {
				throw new IllegalArgumentException("Variable-length path maxHops (" + edge.maxHops()
						+ ") cannot be less than minHops (" + edge.minHops() + ")");
			}
			if (!edge.isUnbounded() && edge.maxHops() > MAX_UNROLLED_PATH_HOPS) {
				throw new IllegalArgumentException("Variable-length path maxHops exceeds limit ("
						+ MAX_UNROLLED_PATH_HOPS + "): " + edge.maxHops());
			}

			if (multiLaneMode) {
				List<String> rels = edge.relationNames().isEmpty()
						? List.of(edge.relationName())
						: edge.relationNames();
				boolean fixedPoint = edge.isUnbounded();
				int hops = fixedPoint ? 1 : Math.max(1, edge.maxHops());
				for (int h = 0; h < hops; h++) {
					for (String r : rels) {
						if (edge.isUndirected()) {
							steps.add(new ScmLaneWalk(r, -1, ScmWalk.Direction.FORWARD_CSR, fixedPoint));
							steps.add(new ScmLaneWalk(r, -1, ScmWalk.Direction.REVERSE_CSC, fixedPoint));
						} else {
							ScmWalk.Direction dir = edge.isForward()
									? ScmWalk.Direction.FORWARD_CSR
									: ScmWalk.Direction.REVERSE_CSC;
							steps.add(new ScmLaneWalk(r, -1, dir, fixedPoint));
						}
					}
				}
			} else {
				// Look for attached edge predicates
				ScmCelExpr predicate = null;
				if (edge.variable() != null) {
					for (CypherParser.WherePredicate pred : query.wherePredicates()) {
						if (pred.targetVar().equals(edge.variable())) {
							String celSource = "edge." + pred.field() + " " + pred.op() + " " + pred.valueOrParam();
							CelAstNode celAst = CelParser.parse(celSource);
							predicate = new ScmCelExpr(celSource, celAst);
						}
					}
				}

				ImpScmNode singleHop = buildSingleLaneHopNode(edge, predicate);
				if (edge.isUnbounded()) {
					String targetRel = edge.relationNames().isEmpty()
							? edge.relationName()
							: edge.relationNames().get(0);
					ScmWalk.Direction dir = edge.isForward()
							? ScmWalk.Direction.FORWARD_CSR
							: ScmWalk.Direction.REVERSE_CSC;
					steps.add(new ScmFixpoint(targetRel != null ? targetRel : "", -1, dir, 100, List.of(singleHop)));
				} else {
					int hops = Math.max(1, edge.maxHops());
					for (int h = 0; h < hops; h++) {
						steps.add(singleHop);
					}
				}
			}
		}

		if (multiLaneMode && quorumK > 0) {
			steps.add(ScmLaneFilterQuorum.of(quorumK));
		}

		// 3. Lower ORDER BY vector rerank or Return Projection
		if (query.orderBy() != null && !query.orderBy().queryVector().isEmpty()) {
			steps.add(ScmVectorRerankDot.fromInlineVector(query.orderBy().queryVector(), 0, query.orderBy().metric()));
		} else if (query.projection().isCount()) {
			boolean hasSetOp = steps.stream().anyMatch(s -> s instanceof ScmSetOp);
			steps.add(hasSetOp ? ScmCollect.count() : ScmCollect.scalar());
		} else {
			steps.add(ScmCollect.bitset());
		}

		ScmProgram program = ScmProgram.ofList(steps);
		return new CompilationResult(program, seedVar, seedParamOrVal);
	}

	private static ImpScmNode buildSingleLaneHopNode(CypherParser.EdgePattern edge, ScmCelExpr predicate) {
		List<String> rels = edge.relationNames().isEmpty() ? List.of(edge.relationName()) : edge.relationNames();
		List<ImpScmNode> branches = new ArrayList<>();
		for (String rel : rels) {
			if (edge.isUndirected()) {
				branches.add(predicate != null ? ScmWalk.forward(rel, predicate) : ScmWalk.forward(rel));
				branches.add(predicate != null ? ScmWalk.reverse(rel, predicate) : ScmWalk.reverse(rel));
			} else if (edge.isForward()) {
				branches.add(predicate != null ? ScmWalk.forward(rel, predicate) : ScmWalk.forward(rel));
			} else {
				branches.add(predicate != null ? ScmWalk.reverse(rel, predicate) : ScmWalk.reverse(rel));
			}
		}
		ImpScmNode acc = branches.get(0);
		for (int i = 1; i < branches.size(); i++) {
			acc = ScmSetOp.union(acc, branches.get(i));
		}
		return acc;
	}
}
