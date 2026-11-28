package org.impulsegraph.compiler.datalog;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.ScmCollect;
import org.impulsegraph.compiler.ast.ScmFixpoint;
import org.impulsegraph.compiler.ast.ScmLaneInitGroups;
import org.impulsegraph.compiler.ast.ScmLaneInitSeeds;
import org.impulsegraph.compiler.ast.ScmLanePruneMask;
import org.impulsegraph.compiler.ast.ScmLaneWalk;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.ast.ScmSetOp;
import org.impulsegraph.compiler.ast.ScmWalk;
import org.impulsegraph.compiler.datalog.StratificationValidator.DependencyEdge;
import org.impulsegraph.compiler.impscm.ImpScmCompiler;
import org.impulsegraph.compiler.impscm.ExprLowerer;
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
 * ImpLog (.implog) Datalog & ReBAC Compiler lowering single-seed and multi-seed
 * Datalog programs into ImpScheme AST and ImpulseVM bytecode (including
 * 0xC0..0xC5 multi-lane bit-sliced execution).
 */
public final class ImpLogCompiler {

	public record ParsedAtom(String predicate, List<String> args, boolean negated, boolean reverseDirection) {
	}

	public record ParsedComparison(String lhs, String op, String rhs) {
	}

	public record ParsedRule(String headPredicate, List<String> headArgs, List<ParsedAtom> body,
			List<ParsedComparison> comparisons) {
		public ParsedRule {
			headArgs = headArgs != null ? List.copyOf(headArgs) : List.of();
			body = body != null ? List.copyOf(body) : List.of();
			comparisons = comparisons != null ? List.copyOf(comparisons) : List.of();
		}

		public ParsedRule(String headPredicate, List<String> headArgs, List<ParsedAtom> body) {
			this(headPredicate, headArgs, body, List.of());
		}
	}

	private ImpLogCompiler() {
	}

	public static ScmProgram compile(String source) {
		return compile(source, List.of(), List.of(), 0, List.of());
	}

	public static ScmProgram compileMultiSeed(String source, List<Long> seeds) {
		return compile(source, seeds, List.of(), 0, List.of());
	}

	public static ScmProgram compileGroups(String source, List<Long> nodeGroupPairs, int numGroups) {
		return compile(source, List.of(), nodeGroupPairs, numGroups, List.of());
	}

	public static ScmProgram compileAllowDenySplit(String source, List<Long> allowSeeds, List<Long> denySeeds) {
		Objects.requireNonNull(allowSeeds, "allowSeeds must not be null");
		Objects.requireNonNull(denySeeds, "denySeeds must not be null");
		int half = Math.max(allowSeeds.size(), denySeeds.size());
		if (half <= 0 || half > 32) {
			throw new IllegalArgumentException("Allow/deny split requires between 1 and 32 lanes per half");
		}
		List<Long> combined = new ArrayList<>(half * 2);
		for (int i = 0; i < half; i++) {
			combined.add(i < allowSeeds.size() ? allowSeeds.get(i) : -1L);
		}
		for (int i = 0; i < half; i++) {
			combined.add(i < denySeeds.size() ? denySeeds.get(i) : -1L);
		}
		ScmProgram base = compile(source, combined, List.of(), 0, List.of());
		List<ImpScmNode> steps = new ArrayList<>();
		for (ImpScmNode step : base.steps()) {
			if (step instanceof ScmCollect) {
				steps.add(ScmLanePruneMask.allowDenySplit(half));
			}
			steps.add(step);
		}
		return new ScmProgram(steps);
	}

	public static CompiledQuery compileToExecutable(String source, ImpulseGraphSnapshot snapshot, Arena arena) {
		ScmProgram prog = compile(source);
		return CompiledQuery.fromBytecode(prog, snapshot);
	}

	public static CompiledQuery compileMultiSeedToExecutable(String source, List<Long> seeds,
			ImpulseGraphSnapshot snapshot, Arena arena) {
		ScmProgram prog = compileMultiSeed(source, seeds);
		return CompiledQuery.fromBytecode(prog, snapshot);
	}

	/**
	 * Parses all Datalog rules (including comparison predicates with
	 * {@code DECIMAL(P, S)} literals and {@code CAST(...)}) from the given ImpLog
	 * source.
	 */
	public static List<ParsedRule> parseRules(String source) {
		Objects.requireNonNull(source, "source must not be null");
		List<ParsedRule> rules = new ArrayList<>();
		String[] lines = source.split("\\r?\\n");
		for (String rawLine : lines) {
			String trimmed = stripInlineComment(rawLine).trim();
			if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("%") || trimmed.startsWith("#")
					|| trimmed.startsWith(".decl") || trimmed.startsWith("?-")) {
				continue;
			}
			int arrowPos = trimmed.indexOf(":-");
			if (arrowPos < 0) {
				continue;
			}
			String headStr = trimmed.substring(0, arrowPos).trim();
			String bodyStr = trimmed.substring(arrowPos + 2).trim();
			while (bodyStr.endsWith(".") || bodyStr.endsWith(" ") || bodyStr.endsWith("\t")) {
				bodyStr = bodyStr.substring(0, bodyStr.length() - 1).trim();
			}
			ParsedAtom headAtom = parseAtom(headStr);
			if (headAtom.predicate().isEmpty()) {
				continue;
			}
			List<String> rawBodyTerms = splitTopLevelAtoms(bodyStr);
			List<ParsedAtom> bodyAtoms = new ArrayList<>();
			List<ParsedComparison> comparisons = new ArrayList<>();
			for (String rawTerm : rawBodyTerms) {
				ParsedComparison cmp = tryParseComparison(rawTerm);
				if (cmp != null) {
					// Validate expression syntax eagerly (rejects malformed literals like 1e5d)
					ExprLowerer.parseExpr(cmp.lhs() + " " + cmp.op() + " " + cmp.rhs());
					comparisons.add(cmp);
				} else {
					ParsedAtom atom = parseAtom(rawTerm);
					if (!atom.predicate().isEmpty()) {
						bodyAtoms.add(atom);
					}
				}
			}
			rules.add(new ParsedRule(headAtom.predicate(), headAtom.args(), bodyAtoms, comparisons));
		}
		return rules;
	}

	/**
	 * Lowers an ImpLog Datalog program (including decimal comparison predicates in
	 * rule bodies) into canonical ImpScheme v0.11 S-expression IR.
	 */
	public static String lowerToImpScheme(String source, ImpulseGraphSnapshot snapshot) {
		return lowerToImpScheme(source, snapshot, Map.of());
	}

	/**
	 * Lowers an ImpLog Datalog program with optional explicit parameter types into
	 * canonical ImpScheme v0.11 S-expression IR.
	 */
	public static String lowerToImpScheme(String source, ImpulseGraphSnapshot snapshot,
			Map<String, String> explicitParamTypes) {
		List<ParsedRule> rules = parseRules(source);
		if (rules.isEmpty()) {
			throw new IllegalArgumentException("PARSE_ERROR: no Datalog rules found");
		}
		ParsedRule rule = rules.get(rules.size() - 1);
		if (rule.body().isEmpty()) {
			throw new IllegalArgumentException("PARSE_ERROR: Datalog rule has empty body");
		}

		Map<String, String> paramDecls = new LinkedHashMap<>();
		if (explicitParamTypes != null) {
			for (Map.Entry<String, String> e : explicitParamTypes.entrySet()) {
				String pname = e.getKey().startsWith("$") ? e.getKey() : "$" + e.getKey();
				paramDecls.put(pname, ExprLowerer.normalizeParamType(e.getValue()));
			}
		}

		ParsedAtom primaryAtom = rule.body().get(0);
		String matchedRel = findMatchingRelation(primaryAtom.predicate(), snapshot);

		// Edge-relation rule: e.g. high_value(X, Y) :- transfer(X, Y, Amt), Amt >
		// 19.99d.
		if (matchedRel != null && primaryAtom.args().size() >= 2) {
			String srcVar = primaryAtom.args().get(0);
			String tgtVar = primaryAtom.args().get(1);
			String attrVar = primaryAtom.args().size() >= 3 ? primaryAtom.args().get(2) : null;
			String edgeAttrName = attrVar != null ? resolveEdgeAttrName(matchedRel, attrVar, snapshot) : null;
			String srcDom = resolveRelSrcDom(matchedRel, snapshot);
			String tgtDom = resolveRelTgtDom(matchedRel, snapshot);
			String dir = primaryAtom.reverseDirection() ? ":rev" : ":fwd";

			// Infer $param type if compared against a DECIMAL64 edge attribute
			int[] edgeDecPS = (attrVar != null && edgeAttrName != null)
					? lookupEdgeDecimalPS(snapshot, matchedRel, edgeAttrName)
					: null;
			if (edgeDecPS != null) {
				for (ParsedComparison cmp : rule.comparisons()) {
					inferParamFromComparison(cmp, attrVar, edgeDecPS, paramDecls);
				}
			}

			StringBuilder scm = new StringBuilder("(query\n");
			for (Map.Entry<String, String> p : paramDecls.entrySet()) {
				scm.append("  (param ").append(p.getKey()).append(' ').append(p.getValue()).append(")\n");
			}
			scm.append("  (let ([__src (domain-all \"").append(srcDom).append("\")]\n");
			if (rule.comparisons().isEmpty()) {
				scm.append("        [__res (walk ").append(dir).append(" \"").append(matchedRel)
						.append("\" __src)])\n");
			} else {
				List<String> hoistBindings = new ArrayList<>();
				String whereExpr = lowerEdgeComparisons(rule.comparisons(), attrVar, edgeAttrName, edgeDecPS,
						hoistBindings);
				for (String hb : hoistBindings) {
					scm.append("        ").append(hb).append('\n');
				}
				scm.append("        [__out (seed \"").append(tgtDom).append("\")]\n");
				scm.append("        [__res (edge-stream ").append(dir).append(" \"").append(matchedRel)
						.append("\" __src (lambda (").append(srcVar).append(' ').append(tgtVar).append(" __e) (where ")
						.append(whereExpr).append(") (yield-frontier __out ").append(tgtVar).append(")))])\n");
			}
			scm.append("    (collect :frontier-ids \"").append(rule.headPredicate()).append("\" __res)))");
			return scm.toString();
		}

		// Node-attribute rule: e.g. rich(X) :- account(X, Bal), Bal >= 1000.00d.
		String matchedDom = findMatchingDomain(primaryAtom.predicate(), snapshot);
		String nodeVar = primaryAtom.args().isEmpty() ? "x" : primaryAtom.args().get(0);
		String attrVar = primaryAtom.args().size() >= 2 ? primaryAtom.args().get(1) : null;
		String nodeAttrName = attrVar != null ? resolveNodeAttrName(matchedDom, attrVar, snapshot) : null;

		if (attrVar != null && nodeAttrName != null) {
			int[] decPS = lookupNodeDecimalPS(snapshot, matchedDom, nodeAttrName);
			if (decPS != null) {
				for (ParsedComparison cmp : rule.comparisons()) {
					inferParamFromComparison(cmp, attrVar, decPS, paramDecls);
				}
			}
		}

		StringBuilder scm = new StringBuilder("(query\n");
		for (Map.Entry<String, String> p : paramDecls.entrySet()) {
			scm.append("  (param ").append(p.getKey()).append(' ').append(p.getValue()).append(")\n");
		}
		scm.append("  (let ([__all (domain-all \"").append(matchedDom).append("\")]");
		String curF = "__all";
		int fIdx = 0;
		for (ParsedComparison cmp : rule.comparisons()) {
			String nextF = "__f" + (++fIdx);
			String lowered = ExprLowerer.lowerExpr(cmp.lhs() + " " + cmp.op() + " " + cmp.rhs(),
					id -> id.equals(attrVar) ? "(attr " + nodeVar + " \"" + nodeAttrName + "\")" : id,
					(v, m) -> "(attr " + v + " \"" + m + "\")");
			scm.append("\n        [").append(nextF).append(" (node-filter ").append(curF).append(" (lambda (")
					.append(nodeVar).append(") ").append(lowered).append("))]");
			curF = nextF;
		}
		scm.append(")\n    (collect :frontier-ids \"").append(rule.headPredicate()).append("\" ").append(curF)
				.append(")))");
		return scm.toString();
	}

	/**
	 * Compiles an ImpLog Datalog program directly to validated v0.11 bytecode via
	 * {@link ImpScmCompiler}.
	 */
	public static ImpScmCompiler.CompileResult compileToBytecode(String source, ImpulseGraphSnapshot snapshot) {
		return compileToBytecode(source, snapshot, Map.of());
	}

	/**
	 * Compiles an ImpLog Datalog program with explicit parameter types directly to
	 * validated v0.11 bytecode via {@link ImpScmCompiler}.
	 */
	public static ImpScmCompiler.CompileResult compileToBytecode(String source, ImpulseGraphSnapshot snapshot,
			Map<String, String> paramTypes) {
		String scm = lowerToImpScheme(source, snapshot, paramTypes);
		return ImpScmCompiler.compile(scm, snapshot, true);
	}

	private static String lowerEdgeComparisons(List<ParsedComparison> comparisons, String attrVar,
			String edgeAttrName, int[] edgeDecPS, List<String> hoistBindings) {
		java.util.function.Function<String, String> identResolver = id -> id.equals(attrVar)
				? "(edge-attr __e \"" + edgeAttrName + "\")"
				: id;
		List<String> parts = new ArrayList<>();
		int hoistIdx = 0;
		for (ParsedComparison cmp : comparisons) {
			String lhsScm = ExprLowerer.lowerExpr(cmp.lhs(), identResolver,
					(v, m) -> "(attr " + v + " \"" + m + "\")");
			String rhsScm = ExprLowerer.lowerExpr(cmp.rhs(), identResolver,
					(v, m) -> "(attr " + v + " \"" + m + "\")");
			if (!rhsScm.contains("(edge-attr ") && !rhsScm.contains("(attr ") && !rhsScm.startsWith("$")) {
				String hoistVar = "__ek" + (++hoistIdx);
				if (edgeDecPS != null && cmp.lhs().trim().equals(attrVar) && rhsScm.matches("-?\\d+")) {
					hoistBindings.add("[" + hoistVar + " (cast " + rhsScm + " (decimal 18 " + edgeDecPS[1] + "))]");
				} else {
					hoistBindings.add("[" + hoistVar + " " + rhsScm + "]");
				}
				rhsScm = hoistVar;
			}
			String scmOp = switch (cmp.op()) {
				case "==" -> "=";
				case "<>" -> "!=";
				default -> cmp.op();
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

	private static final java.util.regex.Pattern PARAM_IDENT_PATTERN = java.util.regex.Pattern
			.compile("\\$[A-Za-z_][A-Za-z0-9_]*");

	private static void inferParamFromComparison(ParsedComparison cmp, String attrVar, int[] decPS,
			Map<String, String> paramDecls) {
		String decType = "(decimal " + decPS[0] + " " + decPS[1] + ")";
		if (cmp.lhs().trim().equals(attrVar) && cmp.rhs().contains("$")) {
			java.util.regex.Matcher m = PARAM_IDENT_PATTERN.matcher(cmp.rhs());
			while (m.find()) {
				paramDecls.putIfAbsent(m.group(), decType);
			}
		} else if (cmp.rhs().trim().equals(attrVar) && cmp.lhs().contains("$")) {
			java.util.regex.Matcher m = PARAM_IDENT_PATTERN.matcher(cmp.lhs());
			while (m.find()) {
				paramDecls.putIfAbsent(m.group(), decType);
			}
		}
	}

	private static String findMatchingRelation(String predName, ImpulseGraphSnapshot snapshot) {
		if (snapshot == null || snapshot.getRelationNames() == null) {
			return null;
		}
		for (String rel : snapshot.getRelationNames()) {
			if (rel.equalsIgnoreCase(predName)) {
				return rel;
			}
		}
		return null;
	}

	private static String findMatchingDomain(String predName, ImpulseGraphSnapshot snapshot) {
		if (snapshot != null && snapshot.getDomainNames() != null) {
			for (String dom : snapshot.getDomainNames()) {
				if (dom.equalsIgnoreCase(predName)) {
					return dom;
				}
			}
			if (!snapshot.getDomainNames().isEmpty()) {
				return snapshot.getDomainNames().iterator().next();
			}
		}
		return predName;
	}

	private static String resolveRelSrcDom(String relName, ImpulseGraphSnapshot snapshot) {
		if (snapshot instanceof GraphSnapshot gs) {
			RelationSnapshot rs = gs.getRelationSnapshot(relName);
			if (rs != null) {
				String dn = domainNameById(gs, rs.getSrcDomainId());
				if (dn != null) {
					return dn;
				}
			}
		}
		return findMatchingDomain("Node", snapshot);
	}

	private static String resolveRelTgtDom(String relName, ImpulseGraphSnapshot snapshot) {
		if (snapshot instanceof GraphSnapshot gs) {
			RelationSnapshot rs = gs.getRelationSnapshot(relName);
			if (rs != null) {
				String dn = domainNameById(gs, rs.getTgtDomainId());
				if (dn != null) {
					return dn;
				}
			}
		}
		return findMatchingDomain("Node", snapshot);
	}

	private static String domainNameById(GraphSnapshot gs, int domId) {
		for (String dn : gs.getDomainNames()) {
			BinarySnapshotLoader.LoadedDomain ld = gs.getDomain(dn);
			if (ld != null && ld.domainId() == domId) {
				return dn;
			}
		}
		return null;
	}

	private static String resolveEdgeAttrName(String relName, String varHint, ImpulseGraphSnapshot snapshot) {
		if (snapshot != null) {
			RelationSnapshot rs = snapshot.getRelationSnapshot(relName);
			if (rs instanceof org.impulsegraph.storage.csr.RelationSnapshot csrRel) {
				List<BinarySnapshotLoader.LoadedAttribute> attrs = csrRel.getLoadedAttributes();
				String hintLower = varHint.toLowerCase();
				for (BinarySnapshotLoader.LoadedAttribute la : attrs) {
					if (la.name().equalsIgnoreCase(varHint)) {
						return la.name();
					}
				}
				for (BinarySnapshotLoader.LoadedAttribute la : attrs) {
					if (la.name().toLowerCase().startsWith(hintLower)
							|| hintLower.startsWith(la.name().toLowerCase())) {
						return la.name();
					}
				}
				for (BinarySnapshotLoader.LoadedAttribute la : attrs) {
					if (la.typeCode() == 0x15 || la.typeCode() == (byte) 0x95) {
						return la.name();
					}
				}
				if (!attrs.isEmpty()) {
					return attrs.get(0).name();
				}
			}
		}
		return varHint.toLowerCase();
	}

	private static String resolveNodeAttrName(String domName, String varHint, ImpulseGraphSnapshot snapshot) {
		if (snapshot instanceof GraphSnapshot gs) {
			BinarySnapshotLoader.LoadedDomain ld = gs.getDomain(domName);
			if (ld != null) {
				String hintLower = varHint.toLowerCase();
				for (BinarySnapshotLoader.LoadedAttribute la : ld.attributes()) {
					if (la.name().equalsIgnoreCase(varHint)) {
						return la.name();
					}
				}
				for (BinarySnapshotLoader.LoadedAttribute la : ld.attributes()) {
					if (la.name().toLowerCase().startsWith(hintLower)
							|| hintLower.startsWith(la.name().toLowerCase())) {
						return la.name();
					}
				}
				for (BinarySnapshotLoader.LoadedAttribute la : ld.attributes()) {
					if (la.typeCode() == 0x15 || la.typeCode() == (byte) 0x95) {
						return la.name();
					}
				}
				if (!ld.attributes().isEmpty()) {
					return ld.attributes().get(0).name();
				}
			}
		}
		return varHint.toLowerCase();
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

	static ParsedComparison tryParseComparison(String rawTerm) {
		String t = rawTerm.trim();
		int depth = 0;
		boolean inStr = false;
		char quote = 0;
		int n = t.length();
		for (int i = 0; i < n; i++) {
			char c = t.charAt(i);
			if (inStr) {
				if (c == '\\' && i + 1 < n) {
					i++;
				} else if (c == quote) {
					inStr = false;
				}
				continue;
			}
			if (c == '"' || c == '\'') {
				inStr = true;
				quote = c;
				continue;
			}
			if (c == '(') {
				depth++;
				continue;
			}
			if (c == ')') {
				depth = Math.max(0, depth - 1);
				continue;
			}
			if (depth == 0) {
				if (i + 1 < n) {
					String two = t.substring(i, i + 2);
					if (two.equals(">=") || two.equals("<=") || two.equals("!=") || two.equals("<>")
							|| two.equals("==")) {
						return new ParsedComparison(t.substring(0, i).trim(), two, t.substring(i + 2).trim());
					}
				}
				if (c == '>' || c == '<' || c == '=') {
					return new ParsedComparison(t.substring(0, i).trim(), String.valueOf(c), t.substring(i + 1).trim());
				}
			}
		}
		return null;
	}

	private static ScmProgram compile(String source, List<Long> explicitSeeds, List<Long> explicitGroupPairs,
			int explicitNumGroups, List<Long> explicitDenyNodes) {
		Objects.requireNonNull(source, "source must not be null");

		List<Long> seeds = new ArrayList<>(explicitSeeds);
		List<Long> groupPairs = new ArrayList<>(explicitGroupPairs);
		List<Long> denyNodes = new ArrayList<>(explicitDenyNodes);
		int maxGroup = explicitNumGroups - 1;

		List<ParsedRule> rules = new ArrayList<>();
		List<DependencyEdge> edges = new ArrayList<>();

		String[] lines = source.split("\\r?\\n");
		for (String rawLine : lines) {
			String trimmed = stripInlineComment(rawLine).trim();
			if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("%") || trimmed.startsWith("#")
					|| trimmed.startsWith(".decl") || trimmed.startsWith("?-")) {
				continue;
			}

			int arrowPos = trimmed.indexOf(":-");
			if (arrowPos < 0) {
				parseFactOrDirective(trimmed, seeds, groupPairs, denyNodes);
				continue;
			}

			String headStr = trimmed.substring(0, arrowPos).trim();
			String bodyStr = trimmed.substring(arrowPos + 2).trim();
			while (bodyStr.endsWith(".") || bodyStr.endsWith(" ") || bodyStr.endsWith("\t")) {
				bodyStr = bodyStr.substring(0, bodyStr.length() - 1).trim();
			}

			ParsedAtom headAtom = parseAtom(headStr);
			if (headAtom.predicate().isEmpty()) {
				continue;
			}

			List<String> rawBodyTerms = splitTopLevelAtoms(bodyStr);
			List<ParsedAtom> bodyAtoms = new ArrayList<>();
			List<ParsedComparison> comparisons = new ArrayList<>();
			for (String rawTerm : rawBodyTerms) {
				ParsedComparison cmp = tryParseComparison(rawTerm);
				if (cmp != null) {
					ExprLowerer.parseExpr(cmp.lhs() + " " + cmp.op() + " " + cmp.rhs());
					comparisons.add(cmp);
				} else {
					ParsedAtom atom = parseAtom(rawTerm);
					if (!atom.predicate().isEmpty()) {
						bodyAtoms.add(atom);
						edges.add(new DependencyEdge(headAtom.predicate(), atom.predicate(), atom.negated()));
					}
				}
			}
			rules.add(new ParsedRule(headAtom.predicate(), headAtom.args(), bodyAtoms, comparisons));
		}

		StratificationValidator.validate(edges);

		for (int i = 1; i < groupPairs.size(); i += 2) {
			maxGroup = Math.max(maxGroup, groupPairs.get(i).intValue());
		}
		int numGroups = Math.max(explicitNumGroups, maxGroup + 1);
		boolean multiLane = !seeds.isEmpty() || !groupPairs.isEmpty();

		Map<String, List<ParsedRule>> rulesByHead = new LinkedHashMap<>();
		for (ParsedRule r : rules) {
			rulesByHead.computeIfAbsent(r.headPredicate(), k -> new ArrayList<>()).add(r);
		}

		List<ImpScmNode> steps = new ArrayList<>();
		if (!groupPairs.isEmpty()) {
			steps.add(ScmLaneInitGroups.fromInlinePairs(groupPairs, Math.max(1, numGroups)));
		} else if (!seeds.isEmpty()) {
			steps.add(ScmLaneInitSeeds.fromInlineSeeds(seeds));
		}

		Set<String> recursivePreds = new LinkedHashSet<>();
		for (String headPred : rulesByHead.keySet()) {
			if (StratificationValidator.isRecursive(headPred, edges)) {
				recursivePreds.add(headPred);
			}
		}

		Set<String> emittedBaseRels = new LinkedHashSet<>();
		List<String> negatedPreds = new ArrayList<>();

		for (ParsedRule rule : rules) {
			boolean ruleIsRecursive = recursivePreds.contains(rule.headPredicate());
			for (ParsedAtom atom : rule.body()) {
				if (atom.negated()) {
					negatedPreds.add(atom.predicate());
					continue;
				}
				if (atom.predicate().equals(rule.headPredicate())) {
					continue;
				}
				if (rulesByHead.containsKey(atom.predicate()) && !recursivePreds.contains(atom.predicate())) {
					continue;
				}
				ScmWalk.Direction dir = atom.reverseDirection()
						? ScmWalk.Direction.REVERSE_CSC
						: ScmWalk.Direction.FORWARD_CSR;
				if (multiLane) {
					if (!ruleIsRecursive || emittedBaseRels.add(atom.predicate())) {
						steps.add(new ScmLaneWalk(atom.predicate(), -1, dir, ruleIsRecursive));
					}
				} else {
					if (ruleIsRecursive) {
						if (emittedBaseRels.add(atom.predicate())) {
							steps.add(new ScmFixpoint(atom.predicate(), -1, dir, 100,
									List.of(new ScmWalk(atom.predicate(), -1, dir))));
						}
					} else {
						steps.add(new ScmWalk(atom.predicate(), -1, dir));
					}
				}
			}
		}

		if (multiLane) {
			if (!denyNodes.isEmpty()) {
				steps.add(ScmLanePruneMask.fromInlineDeny(denyNodes));
			} else if (!negatedPreds.isEmpty()) {
				steps.add(ScmLanePruneMask.fromRegister(0, 0));
			}
		} else if (!negatedPreds.isEmpty() && !steps.isEmpty()) {
			ImpScmNode posNode = steps.size() == 1 ? steps.get(0) : new ScmProgram(new ArrayList<>(steps));
			steps.clear();
			for (String negPred : negatedPreds) {
				posNode = ScmSetOp.diff(posNode, ScmWalk.forward(negPred));
			}
			steps.add(posNode);
		}

		if (steps.isEmpty()) {
			steps.add(ScmWalk.forward("default"));
		}
		steps.add(ScmCollect.bitset());
		return new ScmProgram(steps);
	}

	private static String stripInlineComment(String line) {
		boolean inStr = false;
		char quote = 0;
		int n = line.length();
		for (int i = 0; i < n; i++) {
			char c = line.charAt(i);
			if (inStr) {
				if (c == '\\' && i + 1 < n) {
					i++;
				} else if (c == quote) {
					inStr = false;
				}
				continue;
			}
			if (c == '"' || c == '\'') {
				inStr = true;
				quote = c;
			} else if (c == '%' || (c == '/' && i + 1 < n && line.charAt(i + 1) == '/')) {
				return line.substring(0, i);
			}
		}
		return line;
	}

	private static void parseFactOrDirective(String line, List<Long> seeds, List<Long> groupPairs,
			List<Long> denyNodes) {
		String s = line;
		while (s.endsWith(".")) {
			s = s.substring(0, s.length() - 1).trim();
		}
		if (s.startsWith(".")) {
			s = s.substring(1).trim();
		}
		ParsedAtom atom = parseAtom(s);
		String pred = atom.predicate().toLowerCase();
		if (pred.equals("seed") || pred.equals("seeds")) {
			for (String arg : atom.args()) {
				parseLongOpt(arg).ifPresent(seeds::add);
			}
		} else if (pred.equals("group") || pred.equals("groups")) {
			if (atom.args().size() >= 2) {
				var u = parseLongOpt(atom.args().get(0));
				var g = parseLongOpt(atom.args().get(1));
				if (u.isPresent() && g.isPresent()) {
					groupPairs.add(u.get());
					groupPairs.add(g.get());
				}
			}
		} else if (pred.equals("denied") || pred.equals("deny")) {
			for (String arg : atom.args()) {
				parseLongOpt(arg).ifPresent(denyNodes::add);
			}
		}
	}

	private static java.util.Optional<Long> parseLongOpt(String token) {
		try {
			return java.util.Optional.of(Long.parseLong(token.trim()));
		} catch (NumberFormatException e) {
			return java.util.Optional.empty();
		}
	}

	private static List<String> splitTopLevelAtoms(String body) {
		List<String> out = new ArrayList<>();
		int depth = 0;
		boolean inStr = false;
		char quote = 0;
		int start = 0;
		for (int i = 0; i < body.length(); i++) {
			char c = body.charAt(i);
			if (inStr) {
				if (c == '\\' && i + 1 < body.length()) {
					i++;
				} else if (c == quote) {
					inStr = false;
				}
				continue;
			}
			if (c == '"' || c == '\'') {
				inStr = true;
				quote = c;
			} else if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth = Math.max(0, depth - 1);
			} else if (c == ',' && depth == 0) {
				out.add(body.substring(start, i).trim());
				start = i + 1;
			}
		}
		if (start < body.length()) {
			out.add(body.substring(start).trim());
		}
		return out;
	}

	private static ParsedAtom parseAtom(String raw) {
		String t = raw.trim();
		boolean neg = false;
		if (t.startsWith("!")) {
			neg = true;
			t = t.substring(1).trim();
		} else if (t.regionMatches(true, 0, "not ", 0, 4)) {
			neg = true;
			t = t.substring(4).trim();
		}
		int lp = t.indexOf('(');
		int rp = t.lastIndexOf(')');
		String pred;
		List<String> args = new ArrayList<>();
		if (lp >= 0 && rp > lp) {
			pred = t.substring(0, lp).replaceAll("\\s+", "");
			String inside = t.substring(lp + 1, rp).trim();
			if (!inside.isEmpty()) {
				for (String part : splitTopLevelAtoms(inside)) {
					args.add(part.trim());
				}
			}
		} else {
			pred = t.replaceAll("\\s+", "");
		}
		boolean rev = pred.endsWith("_rev") || pred.endsWith("^T");
		if (pred.endsWith("^T")) {
			pred = pred.substring(0, pred.length() - 2);
		}
		return new ParsedAtom(pred, args, neg, rev);
	}
}
