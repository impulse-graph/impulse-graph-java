package org.impulsegraph.vm.harness;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.traversal.DomainView;
import org.impulsegraph.api.traversal.MultiLaneResult;
import org.impulsegraph.api.traversal.MultiLaneTraversal;
import org.impulsegraph.api.traversal.ScoredNode;
import org.impulsegraph.api.traversal.Traversal;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.vm.traversal.DefaultMultiLaneTraversal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Phase B Canonical Batch JSONL Query Runner for Java 25 Impulse Engine.
 *
 * <p>
 * Opens an immutable Spec v0.10.0 {@code .imps} snapshot once via
 * {@link BinarySnapshotLoader#loadSnapshot(Path)}, reads JSONL query
 * specifications line-by-line, executes every query strictly through native
 * Java 25 Impulse engine APIs ({@link DomainView}, {@link Traversal},
 * {@link MultiLaneTraversal}, {@code ImpulseVM}), and emits one single-line
 * JSON response per input query to {@code stdout}.
 */
public final class KleisliJsonQueryRunner {

	private KleisliJsonQueryRunner() {
	}

	private static final class UnsupportedQueryException extends RuntimeException {
		UnsupportedQueryException(String reason) {
			super(reason);
		}
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("Usage: KleisliJsonQueryRunner <snapshot.imps> <queries.jsonl>");
			System.exit(2);
		}
		Path snapshotPath = Path.of(args[0]);
		Path queriesPath = Path.of(args[1]);

		try (BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotPath,
				java.lang.foreign.Arena.ofShared());
				BufferedReader reader = Files.newBufferedReader(queriesPath, StandardCharsets.UTF_8)) {
			ImpulseGraphSnapshot snap = loaded.graph();
			BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				String jsonOut = executeJsonLine(snap, line);
				out.write(jsonOut);
				out.newLine();
			}
			out.flush();
		}
	}

	/**
	 * Executes a single JSON query specification line against {@code snap} and
	 * returns a single-line JSON response string.
	 */
	@SuppressWarnings("unchecked")
	public static String executeJsonLine(ImpulseGraphSnapshot snap, String line) {
		String queryId = "";
		try {
			Object parsed = new JsonParser(line).parseValue();
			if (!(parsed instanceof Map<?, ?> rawMap)) {
				return formatError("", "Query JSON root must be an object");
			}
			Map<String, Object> query = (Map<String, Object>) rawMap;
			queryId = getString(query, "id", "");

			Map<String, Object> anchor = getObject(query, "anchor");
			if (anchor == null) {
				return formatError(queryId, "Missing 'anchor' object");
			}
			List<Object> steps = getArray(query, "steps");
			Map<String, Object> terminal = getObject(query, "terminal");
			if (terminal == null) {
				return formatError(queryId, "Missing 'terminal' object");
			}

			String mode = getString(anchor, "mode", "all");
			boolean isMultiLane = "multilane_seeds".equals(mode) || "multilane_groups".equals(mode);

			if (isMultiLane) {
				MultiLaneTraversal mlTrav = buildMultiLanePipeline(snap, anchor, steps);
				return executeMultiLaneTerminal(queryId, mlTrav, terminal);
			} else {
				Traversal<ImpulseBitSet> trav = buildSingleLanePipeline(snap, anchor, steps);
				return executeSingleLaneTerminal(queryId, trav, terminal);
			}
		} catch (UnsupportedQueryException uqe) {
			return formatUnsupported(queryId, uqe.getMessage());
		} catch (Throwable t) {
			String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
			return formatError(queryId, msg);
		}
	}

	@SuppressWarnings("unchecked")
	private static Traversal<ImpulseBitSet> buildSingleLanePipeline(ImpulseGraphSnapshot snap,
			Map<String, Object> anchor, List<Object> steps) {
		String domain = getString(anchor, "domain", "");
		if (domain.isEmpty()) {
			throw new IllegalArgumentException("Missing anchor.domain");
		}
		DomainView dv = snap.domain(domain);
		String mode = getString(anchor, "mode", "all");

		Traversal<ImpulseBitSet> trav;
		switch (mode) {
			case "all" -> trav = dv.all();
			case "seeds" -> {
				List<Object> rawSeeds = getArray(anchor, "seeds");
				long[] seeds = toLongArray(rawSeeds);
				trav = dv.from(seeds);
			}
			default -> throw new UnsupportedQueryException("Unsupported single-lane anchor.mode: " + mode);
		}

		if (steps != null) {
			for (Object stepObj : steps) {
				if (!(stepObj instanceof Map<?, ?> rawStep)) {
					throw new IllegalArgumentException("Step must be a JSON object");
				}
				Map<String, Object> step = (Map<String, Object>) rawStep;
				String op = getString(step, "op", "");
				switch (op) {
					case "filter" -> trav = trav.filter(requireString(step, "cel"));
					case "out" -> trav = trav.out(requireString(step, "relation"));
					case "in" -> trav = trav.in(requireString(step, "relation"));
					case "both" -> trav = trav.both(requireString(step, "relation"));
					case "out_union" -> trav = trav.out(toStringArray(getArray(step, "relations")));
					case "in_union" -> trav = trav.in(toStringArray(getArray(step, "relations")));
					case "out_where" ->
						trav = trav.outWhere(requireString(step, "relation"), requireString(step, "cel"));
					case "in_where" -> trav = trav.inWhere(requireString(step, "relation"), requireString(step, "cel"));
					case "repeat_out" ->
						trav = trav.repeatOut(requireString(step, "relation"), getInt(step, "hops", 1));
					case "fixpoint_out" ->
						trav = trav.fixpointOut(requireString(step, "relation"), getInt(step, "max_hops", 64));
					case "set_op" -> {
						String setKind = getString(step, "set_op", "");
						Map<String, Object> branchObj = getObject(step, "branch");
						if (branchObj == null) {
							throw new IllegalArgumentException("set_op missing 'branch'");
						}
						Map<String, Object> branchAnchor = getObject(branchObj, "anchor");
						List<Object> branchSteps = getArray(branchObj, "steps");
						if (branchAnchor == null) {
							throw new IllegalArgumentException("set_op branch missing 'anchor'");
						}
						String bMode = getString(branchAnchor, "mode", "all");
						Traversal<ImpulseBitSet> branchTrav;
						if ("multilane_seeds".equals(bMode) || "multilane_groups".equals(bMode)) {
							branchTrav = buildMultiLanePipeline(snap, branchAnchor, branchSteps).toQuorumTraversal(1);
						} else {
							branchTrav = buildSingleLanePipeline(snap, branchAnchor, branchSteps);
						}
						trav = switch (setKind) {
							case "union" -> trav.union(branchTrav);
							case "intersect" -> trav.intersect(branchTrav);
							case "difference" -> trav.difference(branchTrav);
							default -> throw new UnsupportedQueryException("Unsupported set_op: " + setKind);
						};
					}
					default -> throw new UnsupportedQueryException("Unsupported single-lane step op: " + op);
				}
			}
		}
		return trav;
	}

	@SuppressWarnings("unchecked")
	private static MultiLaneTraversal buildMultiLanePipeline(ImpulseGraphSnapshot snap, Map<String, Object> anchor,
			List<Object> steps) {
		String domain = getString(anchor, "domain", "");
		if (domain.isEmpty()) {
			throw new IllegalArgumentException("Missing anchor.domain");
		}
		DomainView dv = snap.domain(domain);
		String mode = getString(anchor, "mode", "");

		MultiLaneTraversal mlTrav;
		switch (mode) {
			case "multilane_seeds" -> {
				List<Object> rawSeeds = getArray(anchor, "seeds");
				List<Long> seedList = new ArrayList<>(rawSeeds != null ? rawSeeds.size() : 0);
				if (rawSeeds != null) {
					for (Object o : rawSeeds) {
						seedList.add(asLong(o));
					}
				}
				mlTrav = dv.multiLane().fromSeeds(seedList);
			}
			case "multilane_groups" -> {
				List<Object> rawGroups = getArray(anchor, "groups");
				int maxGroup = -1;
				List<long[]> pairs = new ArrayList<>(rawGroups != null ? rawGroups.size() : 0);
				if (rawGroups != null) {
					for (Object item : rawGroups) {
						if (item instanceof List<?> pair && pair.size() >= 2) {
							long nid = asLong(pair.get(0));
							int gIdx = (int) asLong(pair.get(1));
							if (gIdx >= 0 && gIdx < 64) {
								maxGroup = Math.max(maxGroup, gIdx);
								pairs.add(new long[]{nid, gIdx});
							}
						}
					}
				}
				Map<String, List<Long>> groupMap = new LinkedHashMap<>();
				for (int g = 0; g <= maxGroup; g++) {
					groupMap.put("group_" + g, new ArrayList<>());
				}
				for (long[] p : pairs) {
					List<Long> bucket = groupMap.get("group_" + p[1]);
					if (bucket != null) {
						bucket.add(p[0]);
					}
				}
				mlTrav = dv.multiLane().fromGroups(groupMap);
			}
			default -> throw new UnsupportedQueryException("Unsupported multi-lane anchor.mode: " + mode);
		}

		if (steps != null) {
			for (Object stepObj : steps) {
				if (!(stepObj instanceof Map<?, ?> rawStep)) {
					throw new IllegalArgumentException("Step must be a JSON object");
				}
				Map<String, Object> step = (Map<String, Object>) rawStep;
				String op = getString(step, "op", "");
				switch (op) {
					case "filter" -> mlTrav = mlTrav.filter(requireString(step, "cel"));
					case "out" -> mlTrav = mlTrav.out(requireString(step, "relation"));
					case "in" -> mlTrav = mlTrav.in(requireString(step, "relation"));
					case "both" -> mlTrav = mlTrav.both(requireString(step, "relation"));
					case "out_union" -> mlTrav = mlTrav.outUnion(toStringArray(getArray(step, "relations")));
					case "in_union" -> mlTrav = mlTrav.inUnion(toStringArray(getArray(step, "relations")));
					case "out_where" ->
						mlTrav = mlTrav.outWhere(requireString(step, "relation"), requireString(step, "cel"));
					case "in_where" ->
						mlTrav = mlTrav.inWhere(requireString(step, "relation"), requireString(step, "cel"));
					case "repeat_out" ->
						mlTrav = mlTrav.repeatOut(requireString(step, "relation"), getInt(step, "hops", 1));
					case "fixpoint_out" ->
						mlTrav = mlTrav.fixpointOut(requireString(step, "relation"), getInt(step, "max_hops", 64));
					case "where_quorum" -> {
						int minK = getInt(step, "min_k", 1);
						long reqMask = getUnsignedOrSignedLong(step, "req_mask", -1L);
						long forbidMask = getUnsignedOrSignedLong(step, "forbid_mask", 0L);
						mlTrav = mlTrav.whereQuorum(minK, reqMask, forbidMask);
					}
					default -> throw new UnsupportedQueryException("Unsupported multi-lane step op: " + op);
				}
			}
		}
		return mlTrav;
	}

	private static String executeSingleLaneTerminal(String id, Traversal<ImpulseBitSet> trav,
			Map<String, Object> terminal) {
		String termOp = getString(terminal, "op", "");
		return switch (termOp) {
			case "nodes" -> formatNodes(id, trav.toList());
			case "keys" -> formatKeys(id, trav.toKeyList());
			case "count" -> formatCount(id, trav.count());
			case "sum" -> {
				String expr = resolveScalarExpr(terminal);
				yield formatScalar(id, trav.sum(expr), true);
			}
			case "min" -> {
				String expr = resolveScalarExpr(terminal);
				OptionalDouble od = trav.min(expr);
				yield formatScalar(id, od.isPresent() ? od.getAsDouble() : 0.0, od.isPresent());
			}
			case "max" -> {
				String expr = resolveScalarExpr(terminal);
				OptionalDouble od = trav.max(expr);
				yield formatScalar(id, od.isPresent() ? od.getAsDouble() : 0.0, od.isPresent());
			}
			case "top_k" -> {
				String attr = requireString(terminal, "attr");
				float[] qVec = toFloatArray(getArray(terminal, "query_vec"));
				int metric = getInt(terminal, "metric", 0);
				int topK = getInt(terminal, "top_k", 10);
				List<ScoredNode> top = trav.rerank(qVec, attr, metric).topK(topK);
				yield formatTopK(id, top);
			}
			default -> formatUnsupported(id, "Unsupported single-lane terminal op: " + termOp);
		};
	}

	private static String executeMultiLaneTerminal(String id, MultiLaneTraversal mlTrav, Map<String, Object> terminal) {
		String termOp = getString(terminal, "op", "");
		return switch (termOp) {
			case "lane_masks" -> {
				MultiLaneResult res = mlTrav.collect();
				yield formatLaneMasks(id, res);
			}
			case "set_cover" -> {
				long targetMask = terminal.containsKey("target_mask")
						? getUnsignedOrSignedLong(terminal, "target_mask", -1L)
						: getUnsignedOrSignedLong(terminal, "target", -1L);
				int maxNodes = getInt(terminal, "max_nodes", 64);
				List<Long> cover = (mlTrav instanceof DefaultMultiLaneTraversal dml)
						? dml.collectGreedySetCover(targetMask, maxNodes)
						: mlTrav.collect().findGreedySetCover(targetMask, maxNodes);
				yield formatNodes(id, cover);
			}
			case "nodes" -> formatNodes(id, mlTrav.collect().nodesWithQuorum(1));
			case "keys" -> formatKeys(id, mlTrav.toQuorumTraversal(1).toKeyList());
			case "count" -> formatCount(id, mlTrav.collect().nodesWithQuorum(1).size());
			case "sum", "min", "max" -> executeSingleLaneTerminal(id, mlTrav.toQuorumTraversal(1), terminal);
			case "top_k" -> {
				String attr = requireString(terminal, "attr");
				float[] qVec = toFloatArray(getArray(terminal, "query_vec"));
				int metric = getInt(terminal, "metric", 0);
				int topK = getInt(terminal, "top_k", 10);
				List<ScoredNode> top = mlTrav.collect().topK(qVec, attr, metric, topK);
				yield formatTopK(id, top);
			}
			default -> formatUnsupported(id, "Unsupported multi-lane terminal op: " + termOp);
		};
	}

	private static String resolveScalarExpr(Map<String, Object> terminal) {
		String exprCel = getString(terminal, "expr_cel", "");
		if (!exprCel.isEmpty()) {
			return exprCel;
		}
		return requireString(terminal, "attr");
	}

	// =========================================================================
	// JSON Output Formatters (Single-Line Canonical Protocol §3)
	// =========================================================================

	private static String formatNodes(String id, List<Long> nodes) {
		StringBuilder sb = new StringBuilder(128);
		sb.append("{\"id\":");
		appendJsonString(sb, id);
		sb.append(",\"status\":\"ok\",\"result_type\":\"nodes\",\"nodes\":[");
		for (int i = 0; i < nodes.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(nodes.get(i).longValue());
		}
		sb.append("]}");
		return sb.toString();
	}

	private static String formatKeys(String id, List<String> keys) {
		StringBuilder sb = new StringBuilder(128);
		sb.append("{\"id\":");
		appendJsonString(sb, id);
		sb.append(",\"status\":\"ok\",\"result_type\":\"keys\",\"keys\":[");
		for (int i = 0; i < keys.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			appendJsonString(sb, keys.get(i));
		}
		sb.append("]}");
		return sb.toString();
	}

	private static String formatCount(String id, long count) {
		StringBuilder sb = new StringBuilder(96);
		sb.append("{\"id\":");
		appendJsonString(sb, id);
		sb.append(",\"status\":\"ok\",\"result_type\":\"count\",\"count\":").append(count).append('}');
		return sb.toString();
	}

	private static String formatScalar(String id, double value, boolean present) {
		StringBuilder sb = new StringBuilder(96);
		sb.append("{\"id\":");
		appendJsonString(sb, id);
		sb.append(",\"status\":\"ok\",\"result_type\":\"scalar\",\"scalar\":");
		if (!present || Double.isNaN(value) || Double.isInfinite(value)) {
			sb.append("null");
		} else {
			double norm = (value == 0.0) ? 0.0 : value;
			sb.append(Double.toString(norm));
		}
		sb.append('}');
		return sb.toString();
	}

	private static String formatLaneMasks(String id, MultiLaneResult res) {
		StringBuilder sb = new StringBuilder(160);
		sb.append("{\"id\":");
		appendJsonString(sb, id);
		sb.append(",\"status\":\"ok\",\"result_type\":\"lane_masks\",\"lanes\":[");
		boolean first = true;
		int n = res.domainNodeCount();
		for (int u = 0; u < n; u++) {
			long mask = res.laneMaskFor(u);
			if (mask != 0L) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				sb.append('[').append(u).append(',').append(Long.toUnsignedString(mask)).append(']');
			}
		}
		sb.append("]}");
		return sb.toString();
	}

	private static String formatTopK(String id, List<ScoredNode> top) {
		StringBuilder sb = new StringBuilder(160);
		sb.append("{\"id\":");
		appendJsonString(sb, id);
		sb.append(",\"status\":\"ok\",\"result_type\":\"top_k\",\"nodes\":[");
		for (int i = 0; i < top.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(top.get(i).nodeId());
		}
		sb.append("],\"scores\":[");
		for (int i = 0; i < top.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			float sc = top.get(i).score();
			if (Float.isNaN(sc) || Float.isInfinite(sc)) {
				sb.append("null");
			} else {
				float norm = (sc == 0.0f) ? 0.0f : sc;
				sb.append(Float.toString(norm));
			}
		}
		sb.append("]}");
		return sb.toString();
	}

	private static String formatUnsupported(String id, String reason) {
		StringBuilder sb = new StringBuilder(128);
		sb.append("{\"id\":");
		appendJsonString(sb, id);
		sb.append(",\"status\":\"unsupported\",\"reason\":");
		appendJsonString(sb, reason != null ? reason : "unsupported");
		sb.append('}');
		return sb.toString();
	}

	private static String formatError(String id, String error) {
		StringBuilder sb = new StringBuilder(128);
		sb.append("{\"id\":");
		appendJsonString(sb, id);
		sb.append(",\"status\":\"error\",\"error\":");
		appendJsonString(sb, error != null ? error : "error");
		sb.append('}');
		return sb.toString();
	}

	private static void appendJsonString(StringBuilder sb, String s) {
		if (s == null) {
			sb.append("null");
			return;
		}
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		sb.append('"');
	}

	// =========================================================================
	// Typed JSON Map Extractors
	// =========================================================================

	@SuppressWarnings("unchecked")
	private static Map<String, Object> getObject(Map<String, Object> map, String key) {
		Object v = map.get(key);
		return (v instanceof Map<?, ?> m) ? (Map<String, Object>) m : null;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> getArray(Map<String, Object> map, String key) {
		Object v = map.get(key);
		return (v instanceof List<?> l) ? (List<Object>) l : null;
	}

	private static String getString(Map<String, Object> map, String key, String defVal) {
		Object v = map.get(key);
		return (v instanceof String s) ? s : defVal;
	}

	private static String requireString(Map<String, Object> map, String key) {
		Object v = map.get(key);
		if (v instanceof String s) {
			return s;
		}
		throw new IllegalArgumentException("Missing required string field '" + key + "'");
	}

	private static int getInt(Map<String, Object> map, String key, int defVal) {
		Object v = map.get(key);
		if (v instanceof Number n) {
			return n.intValue();
		}
		if (v instanceof String s) {
			return Integer.parseInt(s);
		}
		return defVal;
	}

	private static long getUnsignedOrSignedLong(Map<String, Object> map, String key, long defVal) {
		Object v = map.get(key);
		if (v == null) {
			return defVal;
		}
		if (v instanceof Long l) {
			return l;
		}
		if (v instanceof Number n) {
			return n.longValue();
		}
		if (v instanceof String s) {
			return s.startsWith("-") ? Long.parseLong(s) : Long.parseUnsignedLong(s);
		}
		return defVal;
	}

	private static long asLong(Object o) {
		if (o instanceof Number n) {
			return n.longValue();
		}
		if (o instanceof String s) {
			return s.startsWith("-") ? Long.parseLong(s) : Long.parseUnsignedLong(s);
		}
		throw new IllegalArgumentException("Expected integer value, got: " + o);
	}

	private static long[] toLongArray(List<Object> list) {
		if (list == null || list.isEmpty()) {
			return new long[0];
		}
		long[] out = new long[list.size()];
		for (int i = 0; i < list.size(); i++) {
			out[i] = asLong(list.get(i));
		}
		return out;
	}

	private static String[] toStringArray(List<Object> list) {
		if (list == null || list.isEmpty()) {
			return new String[0];
		}
		String[] out = new String[list.size()];
		for (int i = 0; i < list.size(); i++) {
			Object o = list.get(i);
			if (!(o instanceof String s)) {
				throw new IllegalArgumentException("Expected string element in array, got: " + o);
			}
			out[i] = s;
		}
		return out;
	}

	private static float[] toFloatArray(List<Object> list) {
		if (list == null || list.isEmpty()) {
			return new float[0];
		}
		float[] out = new float[list.size()];
		for (int i = 0; i < list.size(); i++) {
			Object o = list.get(i);
			if (o instanceof Number n) {
				out[i] = n.floatValue();
			} else if (o instanceof String s) {
				out[i] = Float.parseFloat(s);
			} else {
				throw new IllegalArgumentException("Expected numeric vector element, got: " + o);
			}
		}
		return out;
	}

	// =========================================================================
	// Zero-Dependency Recursive-Descent RFC 8259 JSON Parser
	// =========================================================================

	static final class JsonParser {
		private final String src;
		private final int len;
		private int pos;

		JsonParser(String src) {
			this.src = src;
			this.len = src.length();
			this.pos = 0;
		}

		Object parseValue() {
			skipWs();
			if (pos >= len) {
				throw new IllegalArgumentException("Unexpected end of JSON input");
			}
			char c = src.charAt(pos);
			return switch (c) {
				case '{' -> parseObject();
				case '[' -> parseArray();
				case '"' -> parseString();
				case 't', 'f' -> parseBoolean();
				case 'n' -> parseNull();
				default -> {
					if (c == '-' || (c >= '0' && c <= '9')) {
						yield parseNumber();
					}
					throw new IllegalArgumentException("Unexpected character '" + c + "' at pos " + pos);
				}
			};
		}

		private Map<String, Object> parseObject() {
			pos++; // '{'
			skipWs();
			Map<String, Object> map = new LinkedHashMap<>();
			if (pos < len && src.charAt(pos) == '}') {
				pos++;
				return map;
			}
			while (true) {
				skipWs();
				if (pos >= len || src.charAt(pos) != '"') {
					throw new IllegalArgumentException("Expected string key in object at pos " + pos);
				}
				String key = parseString();
				skipWs();
				if (pos >= len || src.charAt(pos) != ':') {
					throw new IllegalArgumentException("Expected ':' after object key at pos " + pos);
				}
				pos++;
				Object val = parseValue();
				map.put(key, val);
				skipWs();
				if (pos >= len) {
					throw new IllegalArgumentException("Unterminated JSON object");
				}
				char sep = src.charAt(pos);
				if (sep == '}') {
					pos++;
					return map;
				}
				if (sep != ',') {
					throw new IllegalArgumentException("Expected ',' or '}' in object at pos " + pos);
				}
				pos++;
			}
		}

		private List<Object> parseArray() {
			pos++; // '['
			skipWs();
			List<Object> list = new ArrayList<>();
			if (pos < len && src.charAt(pos) == ']') {
				pos++;
				return list;
			}
			while (true) {
				list.add(parseValue());
				skipWs();
				if (pos >= len) {
					throw new IllegalArgumentException("Unterminated JSON array");
				}
				char sep = src.charAt(pos);
				if (sep == ']') {
					pos++;
					return list;
				}
				if (sep != ',') {
					throw new IllegalArgumentException("Expected ',' or ']' in array at pos " + pos);
				}
				pos++;
			}
		}

		private String parseString() {
			pos++; // '"'
			StringBuilder sb = new StringBuilder();
			while (pos < len) {
				char c = src.charAt(pos++);
				if (c == '"') {
					return sb.toString();
				}
				if (c == '\\') {
					if (pos >= len) {
						throw new IllegalArgumentException("Unterminated string escape");
					}
					char esc = src.charAt(pos++);
					switch (esc) {
						case '"' -> sb.append('"');
						case '\\' -> sb.append('\\');
						case '/' -> sb.append('/');
						case 'b' -> sb.append('\b');
						case 'f' -> sb.append('\f');
						case 'n' -> sb.append('\n');
						case 'r' -> sb.append('\r');
						case 't' -> sb.append('\t');
						case 'u' -> {
							if (pos + 4 > len) {
								throw new IllegalArgumentException("Incomplete \\u escape");
							}
							int hex = Integer.parseInt(src.substring(pos, pos + 4), 16);
							pos += 4;
							sb.append((char) hex);
						}
						default -> throw new IllegalArgumentException("Invalid escape '\\" + esc + "'");
					}
				} else {
					sb.append(c);
				}
			}
			throw new IllegalArgumentException("Unterminated JSON string");
		}

		private Object parseNumber() {
			int start = pos;
			boolean isFloat = false;
			if (pos < len && src.charAt(pos) == '-') {
				pos++;
			}
			while (pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') {
				pos++;
			}
			if (pos < len && src.charAt(pos) == '.') {
				isFloat = true;
				pos++;
				while (pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') {
					pos++;
				}
			}
			if (pos < len && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
				isFloat = true;
				pos++;
				if (pos < len && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
					pos++;
				}
				while (pos < len && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') {
					pos++;
				}
			}
			String numStr = src.substring(start, pos);
			if (isFloat) {
				return Double.parseDouble(numStr);
			}
			try {
				return Long.parseLong(numStr);
			} catch (NumberFormatException e) {
				if (!numStr.startsWith("-")) {
					try {
						return Long.parseUnsignedLong(numStr);
					} catch (NumberFormatException ignored) {
					}
				}
				return Double.parseDouble(numStr);
			}
		}

		private Boolean parseBoolean() {
			if (src.startsWith("true", pos)) {
				pos += 4;
				return Boolean.TRUE;
			}
			if (src.startsWith("false", pos)) {
				pos += 5;
				return Boolean.FALSE;
			}
			throw new IllegalArgumentException("Invalid boolean token at pos " + pos);
		}

		private Object parseNull() {
			if (src.startsWith("null", pos)) {
				pos += 4;
				return null;
			}
			throw new IllegalArgumentException("Invalid null token at pos " + pos);
		}

		private void skipWs() {
			while (pos < len) {
				char c = src.charAt(pos);
				if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
					pos++;
				} else {
					break;
				}
			}
		}
	}
}
