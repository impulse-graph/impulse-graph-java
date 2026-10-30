package org.impulsegraph.compiler.cypher;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.cel.CelParser;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.vm.CompiledQuery;

import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Lowers parsed Cypher AST queries into optimized ImpScheme S-Expression AST
 * (ScmProgram) and ImpulseVM bytecode, supporting single-lane and 64-lane
 * multi-seed (`IN [...]`), unbounded Kleene star (`[:REL*]`), undirected/union
 * hops (`-[:R1|R2]-`), quorum filtering (`0xC6`), and hybrid vector ANN/rerank
 * (`0xCA`/`0xCB`).
 */
public final class CypherCompiler {

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
		return ImpOpsBytecodeEmitter.compileToExecutable(res.ast(), snapshot, arena);
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
					steps.add(new ScmFixpoint(new ScmProgram(List.of(singleHop))));
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
			steps.add(ScmCollect.scalar());
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
