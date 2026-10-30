package org.impulsegraph.compiler.datalog;

import org.impulsegraph.api.ImpulseGraphSnapshot;
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
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
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

	public record ParsedRule(String headPredicate, List<String> headArgs, List<ParsedAtom> body) {
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
		return ImpOpsBytecodeEmitter.compileToExecutable(prog, snapshot, arena);
	}

	public static CompiledQuery compileMultiSeedToExecutable(String source, List<Long> seeds,
			ImpulseGraphSnapshot snapshot, Arena arena) {
		ScmProgram prog = compileMultiSeed(source, seeds);
		return ImpOpsBytecodeEmitter.compileToExecutable(prog, snapshot, arena);
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
			for (String rawTerm : rawBodyTerms) {
				ParsedAtom atom = parseAtom(rawTerm);
				if (!atom.predicate().isEmpty()) {
					bodyAtoms.add(atom);
					edges.add(new DependencyEdge(headAtom.predicate(), atom.predicate(), atom.negated()));
				}
			}
			rules.add(new ParsedRule(headAtom.predicate(), headAtom.args(), bodyAtoms));
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
							steps.add(new ScmFixpoint(new ScmProgram(List.of(new ScmWalk(atom.predicate(), -1, dir)))));
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
		int slash = line.indexOf("//");
		if (slash >= 0) {
			line = line.substring(0, slash);
		}
		int pct = line.indexOf('%');
		if (pct >= 0) {
			line = line.substring(0, pct);
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
		int start = 0;
		for (int i = 0; i < body.length(); i++) {
			char c = body.charAt(i);
			if (c == '(') {
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
				for (String part : inside.split(",")) {
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
