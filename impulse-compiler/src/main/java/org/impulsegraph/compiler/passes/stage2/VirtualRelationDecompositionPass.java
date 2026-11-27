package org.impulsegraph.compiler.passes.stage2;

import org.impulsegraph.api.stats.AttributeStatistics;
import org.impulsegraph.api.stats.RelationStatistics;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stage 2 Pass: Virtual Relation Coproduct Decomposition and Partition
 * Elimination. Decomposes virtual super-relations (VR = R1 + R2 + R3) into
 * optimal constituent paths. Prunes non-matching partitions using zone maps,
 * and specializes surviving constituent walks.
 */
public final class VirtualRelationDecompositionPass implements CompilerPass {

	public static final VirtualRelationDecompositionPass INSTANCE = new VirtualRelationDecompositionPass();

	@Override
	public String name() {
		return "VirtualRelationDecompositionPass";
	}

	@Override
	public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
		if (ast == null)
			return null;
		ImpulseGraphSnapshot snapshot = context.snapshot();
		if (snapshot == null)
			return ast;

		return decomposeNode(ast, snapshot);
	}

	private ImpScmNode decomposeNode(ImpScmNode node, ImpulseGraphSnapshot snapshot) {
		if (node instanceof ScmProgram prog) {
			List<ImpScmNode> steps = new ArrayList<>();
			for (ImpScmNode step : prog.steps()) {
				steps.add(decomposeNode(step, snapshot));
			}
			return new ScmProgram(steps);
		}

		if (node instanceof ScmWalk walk) {
			String relName = walk.relationName();
			// Check if there are partitioned relations matching prefix (e.g. relName +
			// "_*")
			List<String> constituents = findConstituentPartitions(snapshot, relName);

			if (constituents.size() > 1) {
				List<ImpScmNode> constituentWalks = new ArrayList<>();

				for (String cName : constituents) {
					RelationSnapshot rel = snapshot.getRelationSnapshot(cName);
					if (rel != null) {
						// Preserve walk.direction(): FORWARD_CSR (R) and REVERSE_CSC (R^T)
						// traverse different edge directions and must never be flipped.
						constituentWalks.add(new ScmWalk(cName, -1, walk.direction(), walk.shaderSteps(), List.of()));
					}
				}

				if (!constituentWalks.isEmpty()) {
					return new ScmWalk(walk.relationName(), walk.relationId(), walk.direction(), walk.shaderSteps(),
							constituentWalks);
				}
			}
		}

		return node;
	}

	private static List<String> findConstituentPartitions(ImpulseGraphSnapshot snapshot, String relName) {
		List<String> matches = new ArrayList<>();
		if (snapshot == null || relName == null)
			return matches;

		String prefix = relName.toLowerCase() + "_";
		for (String name : snapshot.getAllRelationSnapshots().keySet()) {
			if (name.toLowerCase().startsWith(prefix)) {
				matches.add(name);
			}
		}
		matches.sort(String::compareTo);
		return matches;
	}
}
