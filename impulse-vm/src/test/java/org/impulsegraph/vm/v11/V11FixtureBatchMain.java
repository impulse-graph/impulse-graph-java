package org.impulsegraph.vm.v11;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.impulsegraph.vm.v11.V11IsaTables.*;

/**
 * Persistent stdin/stdout JSONL Fixture Batch Runner for ImpulseVM v0.11.0 (Java 25).
 *
 * <p>Implements the exact line protocol of {@code impulse-ocaml --v11-fixture-batch}:
 * each input line is {@code <profile>\t<max_steps|->\t<fixture_json>} and produces one JSON line
 * containing status, PC, flags, dual {@code ICTX v2} hashes, registers, and outputs.
 */
public final class V11FixtureBatchMain {

    private static boolean useJit = false;

    static void setUseJit(boolean enabled) {
        useJit = enabled;
    }

    private V11FixtureBatchMain() {}

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            if ("--jit".equals(arg)) {
                useJit = true;
            }
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            int t1 = line.indexOf('\t');
            int t2 = t1 >= 0 ? line.indexOf('\t', t1 + 1) : -1;
            String profile = (t1 >= 0) ? line.substring(0, t1) : "P0";
            String maxStepsStr = (t1 >= 0 && t2 >= 0) ? line.substring(t1 + 1, t2) : "-";
            String rawJson = (t2 >= 0) ? line.substring(t2 + 1) : line;
            Long maxStepsOverride = ("-".equals(maxStepsStr) || maxStepsStr.isEmpty())
                    ? null
                    : Long.parseLong(maxStepsStr);
            String resp = runFixtureJson(profile, maxStepsOverride, rawJson);
            out.write(resp);
            out.newLine();
            out.flush();
        }
    }

    @SuppressWarnings("unchecked")
    public static String runFixtureJson(String profile, Long maxStepsOverride, String rawJson) {
        Map<String, Object> root = (Map<String, Object>) new MiniJsonParser(rawJson).parseValue();

        List<Object> wordsHex = asList(root.get("words_hex"));
        long[] words = new long[wordsHex.size()];
        for (int i = 0; i < wordsHex.size(); i++) {
            words[i] = parseHexOrDecU64(asStr(wordsHex.get(i)));
        }

        long maxSteps = (maxStepsOverride != null)
                ? maxStepsOverride
                : (root.containsKey("max_steps") ? asLong(root.get("max_steps")) : 100_000L);

        List<Object> dataDirs = asList(root.get("data_directives"));
        List<Object> poolDirs = asList(root.get("const_pool"));

        Map<Integer, Long> domTbl = new HashMap<>();
        Map<Integer, int[]> relMeta = new HashMap<>();
        Map<Integer, Object[]> relCsr = new HashMap<>();
        Map<Integer, boolean[]> relFlags = new HashMap<>();
        Map<Integer, Object[]> relCoo = new HashMap<>();
        Map<Integer, List<List<Integer>>> relDense = new HashMap<>();
        Map<Integer, Map<Integer, V11Vector>> edgeAttrsTbl = new HashMap<>();
        Map<Long, V11Vector> nodeAttrs = new HashMap<>();
        Map<Integer, String[]> keyCatalogs = new HashMap<>();
        Map<Long, String> indexes = new HashMap<>();
        V11Geometry geom = V11Geometry.ofProfile(profile);
        List<Object[]> paramsList = new ArrayList<>();

        for (Object obj : dataDirs) {
            Map<String, Object> d = asMap(obj);
            String kind = asStr(d.get("directive"));
            List<Object> dArgs = asList(d.get("args"));
            Map<String, Object> kv = asMap(d.get("kv"));
            switch (kind) {
                case ".domain" -> {
                    int id = dArgs.isEmpty() ? 0 : asInt(dArgs.get(0));
                    long count = kv.containsKey("count") ? asLong(kv.get("count")) : 0L;
                    domTbl.put(id, count);
                }
                case ".relation" -> {
                    int id = dArgs.isEmpty() ? 0 : asInt(dArgs.get(0));
                    int sd = kv.containsKey("src_dom") ? asInt(kv.get("src_dom")) : 0;
                    int td = kv.containsKey("tgt_dom") ? asInt(kv.get("tgt_dom")) : 0;
                    relMeta.put(id, new int[]{sd, td});
                }
                case ".csr_inline" -> {
                    int rid = kv.containsKey("rel") ? asInt(kv.get("rel")) : 0;
                    List<Object> oL = asList(kv.get("offsets"));
                    List<Object> tL = asList(kv.get("targets"));
                    int[] offs = new int[oL.size()];
                    for (int i = 0; i < oL.size(); i++) offs[i] = asInt(oL.get(i));
                    long[] tgts = new long[tL.size()];
                    for (int i = 0; i < tL.size(); i++) tgts[i] = asLong(tL.get(i));
                    relCsr.put(rid, new Object[]{offs, tgts});
                    relFlags.computeIfAbsent(rid, k -> new boolean[4])[0] = true;
                }
                case ".csc_inline" -> {
                    int rid = kv.containsKey("rel") ? asInt(kv.get("rel")) : 0;
                    relFlags.computeIfAbsent(rid, k -> new boolean[4])[1] = true;
                }
                case ".coo_inline" -> {
                    int rid = kv.containsKey("rel") ? asInt(kv.get("rel")) : 0;
                    List<Object> sL = asList(kv.get("sources"));
                    List<Object> tL = asList(kv.get("targets"));
                    int[] srcs = new int[sL.size()];
                    for (int i = 0; i < sL.size(); i++) srcs[i] = asInt(sL.get(i));
                    long[] tgts = new long[tL.size()];
                    for (int i = 0; i < tL.size(); i++) tgts[i] = asLong(tL.get(i));
                    relCoo.put(rid, new Object[]{srcs, tgts});
                    relFlags.computeIfAbsent(rid, k -> new boolean[4])[2] = true;
                }
                case ".dense_inline" -> {
                    int rid = kv.containsKey("rel") ? asInt(kv.get("rel")) : 0;
                    List<Object> rL = asList(kv.get("rows"));
                    List<List<Integer>> rows = new ArrayList<>();
                    for (Object rObj : rL) {
                        List<Object> rowObjs = asList(rObj);
                        List<Integer> row = new ArrayList<>(rowObjs.size());
                        for (Object x : rowObjs) row.add(asInt(x));
                        rows.add(row);
                    }
                    relDense.put(rid, rows);
                    relFlags.computeIfAbsent(rid, k -> new boolean[4])[3] = true;
                }
                case ".key_catalog" -> {
                    int dom = kv.containsKey("dom") ? asInt(kv.get("dom")) : 0;
                    List<Object> kL = asList(kv.get("keys"));
                    String[] keys = new String[kL.size()];
                    for (int i = 0; i < kL.size(); i++) keys[i] = asStr(kL.get(i));
                    keyCatalogs.put(dom, keys);
                }
                case ".index" -> {
                    int dom = kv.containsKey("dom") ? asInt(kv.get("dom")) : 0;
                    int attr = kv.containsKey("attr") ? asInt(kv.get("attr")) : 0;
                    String idxKind = kv.containsKey("kind") ? asStr(kv.get("kind")) : "sorted";
                    indexes.put(V11SnapshotView.domAttrKey(dom, attr), idxKind);
                }
                case ".node_attr" -> {
                    int dom = kv.containsKey("dom") ? asInt(kv.get("dom")) : 0;
                    int aid = kv.containsKey("attr") ? asInt(kv.get("attr")) : 0;
                    String ty = kv.containsKey("type") ? asStr(kv.get("type")) : "f64";
                    List<Object> vals = asList(kv.get("values"));
                    nodeAttrs.put(V11SnapshotView.domAttrKey(dom, aid), parseVecFromJson(ty, vals));
                }
                case ".edge_attr" -> {
                    int rid = kv.containsKey("rel") ? asInt(kv.get("rel")) : 0;
                    int aid = kv.containsKey("attr") ? asInt(kv.get("attr")) : 0;
                    String ty = kv.containsKey("type") ? asStr(kv.get("type")) : "f64";
                    List<Object> vals = asList(kv.get("values"));
                    edgeAttrsTbl.computeIfAbsent(rid, k -> new HashMap<>()).put(aid, parseVecFromJson(ty, vals));
                }
                case ".tier_thresholds" -> {
                    int t0 = kv.containsKey("tier0_max") ? asInt(kv.get("tier0_max")) : geom.tier0Max();
                    int ps = kv.containsKey("page_shift") ? asInt(kv.get("page_shift")) : geom.pageShift();
                    int mm = kv.containsKey("morsel_max") ? asInt(kv.get("morsel_max")) : geom.morselMax();
                    geom = new V11Geometry(t0, ps, geom.tier2Denom(), mm, geom.maxDop());
                }
                case ".param" -> {
                    int pid = dArgs.isEmpty() ? 0 : asInt(dArgs.get(0));
                    String ty = kv.containsKey("type") ? asStr(kv.get("type")) : "i64";
                    paramsList.add(new Object[]{pid, ty, kv});
                }
                default -> {}
            }
        }

        V11Interpreter.SnapshotBinding bind = null;
        int maxDomCard = 64;
        if (!domTbl.isEmpty() || !relMeta.isEmpty()) {
            int maxDom = -1;
            for (int k : domTbl.keySet()) {
                if (k > maxDom) maxDom = k;
            }
            int[] domCards = new int[maxDom + 1];
            long[] domCardsL = new long[maxDom + 1];
            for (var e : domTbl.entrySet()) {
                int c = (int) Math.min(Integer.MAX_VALUE, e.getValue());
                domCards[e.getKey()] = c;
                domCardsL[e.getKey()] = e.getValue();
                if (c > maxDomCard) maxDomCard = c;
            }
            int maxRel = -1;
            for (int k : relMeta.keySet()) {
                if (k > maxRel) maxRel = k;
            }
            V11SnapshotView.RelView[] relViews = new V11SnapshotView.RelView[maxRel + 1];
            for (int rid = 0; rid <= maxRel; rid++) {
                int[] sdtd = relMeta.getOrDefault(rid, new int[]{0, 0});
                int sd = sdtd[0], td = sdtd[1];
                int nSrc = (sd < domCards.length) ? domCards[sd] : 0;
                boolean[] fl = relFlags.getOrDefault(rid, new boolean[4]);
                int[] rowOff;
                long[] colTgt;
                if (relCsr.containsKey(rid)) {
                    Object[] p = relCsr.get(rid);
                    rowOff = (int[]) p[0];
                    colTgt = (long[]) p[1];
                } else if (relCoo.containsKey(rid)) {
                    Object[] p = relCoo.get(rid);
                    int[] srcs = (int[]) p[0];
                    long[] tgts = (long[]) p[1];
                    @SuppressWarnings("unchecked")
                    List<Long>[] buckets = new List[nSrc];
                    for (int u = 0; u < nSrc; u++) buckets[u] = new ArrayList<>();
                    int lim = Math.min(srcs.length, tgts.length);
                    for (int i = 0; i < lim; i++) {
                        int u = srcs[i];
                        if (u >= 0 && u < nSrc) buckets[u].add(tgts[i]);
                    }
                    rowOff = new int[nSrc + 1];
                    List<Long> flat = new ArrayList<>();
                    for (int u = 0; u < nSrc; u++) {
                        rowOff[u + 1] = rowOff[u] + buckets[u].size();
                        flat.addAll(buckets[u]);
                    }
                    colTgt = new long[flat.size()];
                    for (int i = 0; i < flat.size(); i++) colTgt[i] = flat.get(i);
                } else if (relDense.containsKey(rid)) {
                    List<List<Integer>> rows = relDense.get(rid);
                    rowOff = new int[nSrc + 1];
                    List<Long> flat = new ArrayList<>();
                    for (int u = 0; u < nSrc; u++) {
                        List<Integer> row = (u < rows.size()) ? rows.get(u) : List.of();
                        int cnt = 0;
                        for (int v = 0; v < row.size(); v++) {
                            if (row.get(v) != 0) {
                                flat.add((long) v);
                                cnt++;
                            }
                        }
                        rowOff[u + 1] = rowOff[u] + cnt;
                    }
                    colTgt = new long[flat.size()];
                    for (int i = 0; i < flat.size(); i++) colTgt[i] = flat.get(i);
                } else {
                    rowOff = new int[nSrc + 1];
                    colTgt = new long[0];
                }
                relViews[rid] = new V11SnapshotView.RelView(
                        rid, sd, td, rowOff, colTgt, edgeAttrsTbl.get(rid), fl[0], fl[1], fl[2], fl[3]);
            }
            V11SnapshotView view = new V11SnapshotView(domCardsL, relViews, nodeAttrs, keyCatalogs, indexes);
            bind = new V11Interpreter.SnapshotBinding(domCards, view);
        }

        V11Workspace ws = new V11Workspace(maxDomCard);
        ws.resetAllWithBindings();
        ws.geometry = geom;

        // Populate .param entries
        for (Object[] p : paramsList) {
            int pid = (Integer) p[0];
            String ty = (String) p[1];
            Map<String, Object> kv = (Map<String, Object>) p[2];
            switch (ty) {
                case "frontier" -> {
                    int dom = kv.containsKey("dom") ? asInt(kv.get("dom")) : 0;
                    List<Object> vals = asList(kv.get("values"));
                    long[] ids = new long[vals.size()];
                    for (int i = 0; i < vals.size(); i++) ids[i] = asLong(vals.get(i));
                    ws.params.put(pid, new V11Workspace.ParamFrontier(dom, ids));
                }
                case "multilane" -> {
                    int dom = kv.containsKey("dom") ? asInt(kv.get("dom")) : 0;
                    int width = kv.containsKey("width") ? asInt(kv.get("width")) : 64;
                    int activeLanes = kv.containsKey("active_lanes") ? asInt(kv.get("active_lanes")) : 0;
                    List<Object> rawEntries = asList(kv.get("entries"));
                    List<Long> ns = new ArrayList<>();
                    List<Long> ms = new ArrayList<>();
                    for (Object e : rawEntries) {
                        String s = asStr(e);
                        int colon = s.indexOf(':');
                        if (colon > 0) {
                            ns.add(parseHexOrDecU64(s.substring(0, colon)));
                            ms.add(parseHexOrDecU64(s.substring(colon + 1)));
                        }
                    }
                    long[] nodes = new long[ns.size()];
                    long[] masks = new long[ms.size()];
                    for (int i = 0; i < ns.size(); i++) {
                        nodes[i] = ns.get(i);
                        masks[i] = ms.get(i);
                    }
                    ws.params.put(pid, new V11Workspace.ParamMultiLane(dom, width, activeLanes, nodes, masks));
                }
                case "str", "str_handle" -> {
                    Object v = kv.get("value");
                    ws.params.put(pid, new V11Workspace.ParamStr(v == null ? null : asStr(v)));
                }
                case "i64" -> {
                    Object v = kv.get("value");
                    if (v == null) ws.params.put(pid, new V11Workspace.ParamScalar(SCALAR_I64, false, 0L));
                    else ws.params.put(pid, new V11Workspace.ParamScalar(SCALAR_I64, true, asLong(v)));
                }
                case "u64" -> {
                    Object v = kv.get("value");
                    if (v == null) ws.params.put(pid, new V11Workspace.ParamScalar(SCALAR_U64, false, 0L));
                    else ws.params.put(pid, new V11Workspace.ParamScalar(SCALAR_U64, true, asLong(v)));
                }
                case "f64" -> {
                    Object v = kv.get("value");
                    if (v == null) {
                        ws.params.put(pid, new V11Workspace.ParamScalar(SCALAR_F64, false, 0L));
                    } else {
                        double f = asDouble(v);
                        if (Double.isNaN(f)) ws.params.put(pid, new V11Workspace.ParamScalar(SCALAR_F64, false, 0L));
                        else ws.params.put(pid, new V11Workspace.ParamScalar(SCALAR_F64, true, Double.doubleToRawLongBits(f)));
                    }
                }
                case "bool", "bool_3vl" -> {
                    Object v = kv.get("value");
                    if (v == null) {
                        ws.params.put(pid, new V11Workspace.ParamScalar(SCALAR_BOOL_3VL, false, 0L));
                    } else if (v instanceof Boolean b) {
                        ws.params.put(pid, new V11Workspace.ParamScalar(SCALAR_BOOL_3VL, true, b ? 1L : 0L));
                    } else {
                        ws.params.put(pid, new V11Workspace.ParamScalar(SCALAR_BOOL_3VL, true, asLong(v) != 0L ? 1L : 0L));
                    }
                }
                case "null" -> ws.params.put(pid, new V11Workspace.ParamScalar(SCALAR_NULL_UNSET, false, 0L));
                case "i64_vec", "u64_vec", "f64_vec", "f32", "f32_vec", "str_vec", "bool_3vl_vec" -> {
                    List<Object> vals = asList(kv.get("values"));
                    ws.params.put(pid, new V11Workspace.ParamVec(parseVecFromJson(ty, vals)));
                }
                default -> {}
            }
        }

        // Populate .const_pool entries
        for (Object obj : poolDirs) {
            Map<String, Object> p = asMap(obj);
            List<Object> pArgs = asList(p.get("args"));
            Map<String, Object> kv = asMap(p.get("kv"));
            int pid = pArgs.isEmpty() ? 0 : asInt(pArgs.get(0));
            String ty = kv.containsKey("type") ? asStr(kv.get("type")) : "i64_vec";
            switch (ty) {
                case "str" -> ws.constPool.put(pid, new V11Workspace.PoolStr(asStr(kv.get("value"))));
                case "frontier" -> {
                    List<Object> vals = asList(kv.get("values"));
                    long[] ids = new long[vals.size()];
                    for (int i = 0; i < vals.size(); i++) ids[i] = asLong(vals.get(i));
                    ws.constPool.put(pid, new V11Workspace.PoolFrontier(ids));
                }
                default -> {
                    List<Object> vals = asList(kv.get("values"));
                    ws.constPool.put(pid, new V11Workspace.PoolVec(parseVecFromJson(ty, vals)));
                }
            }
        }

        if (useJit) {
            V11MethodHandleCompiler.CompiledProgram compiled = V11MethodHandleCompiler.compileValidated(words, bind);
            compiled.execute(ws, maxSteps);
        } else {
            V11Interpreter.executeValidated(words, bind, ws, maxSteps);
        }
        V11ContextHash.Digest digest = V11ContextHash.compute(ws);
        return formatResultJson(ws, digest);
    }

    private static V11Vector parseVecFromJson(String ty, List<Object> vals) {
        int n = vals.size();
        int words = Math.max(1, (n + 63) >>> 6);
        return switch (ty) {
            case "i64", "i64_vec", "i32", "i16", "i8" -> {
                long[] data = new long[n];
                long[] valid = new long[words];
                for (int i = 0; i < n; i++) {
                    Object v = vals.get(i);
                    if (v != null) {
                        data[i] = asLong(v);
                        valid[i >>> 6] |= (1L << (i & 63));
                    }
                }
                yield V11Vector.ofI64(data, valid);
            }
            case "u64", "u64_vec", "u32", "u16", "u8" -> {
                long[] data = new long[n];
                long[] valid = new long[words];
                for (int i = 0; i < n; i++) {
                    Object v = vals.get(i);
                    if (v != null) {
                        data[i] = asLong(v);
                        valid[i >>> 6] |= (1L << (i & 63));
                    }
                }
                yield V11Vector.ofU64(data, valid);
            }
            case "str", "str_vec" -> {
                String[] data = new String[n];
                for (int i = 0; i < n; i++) {
                    Object v = vals.get(i);
                    data[i] = (v == null) ? null : asStr(v);
                }
                yield V11Vector.ofStr(data);
            }
            case "bool", "bool_3vl", "bool_3vl_vec" -> {
                long[] valid = new long[words];
                long[] data = new long[words];
                for (int i = 0; i < n; i++) {
                    Object v = vals.get(i);
                    if (v != null) {
                        long bit = 1L << (i & 63);
                        valid[i >>> 6] |= bit;
                        boolean isTrue = (v instanceof Boolean b) ? b : (asLong(v) != 0L);
                        if (isTrue) data[i >>> 6] |= bit;
                    }
                }
                yield V11Vector.ofBool3vl(n, valid, data);
            }
            case "f32_emb", "emb" -> {
                double[][] rows = new double[n][];
                int dim = 0;
                for (int i = 0; i < n; i++) {
                    Object v = vals.get(i);
                    if (v instanceof List<?> rowList) {
                        double[] row = new double[rowList.size()];
                        for (int j = 0; j < rowList.size(); j++) row[j] = asDouble(rowList.get(j));
                        rows[i] = row;
                        if (row.length > dim) dim = row.length;
                    }
                }
                yield V11Vector.ofEmb(dim, rows);
            }
            default -> {
                double[] data = new double[n];
                long[] valid = new long[words];
                for (int i = 0; i < n; i++) {
                    Object v = vals.get(i);
                    if (v != null) {
                        double f = asDouble(v);
                        if (!Double.isNaN(f)) {
                            data[i] = f;
                            valid[i >>> 6] |= (1L << (i & 63));
                        }
                    }
                }
                yield V11Vector.ofF64(data, valid);
            }
        };
    }

    private static String statusName(int st) {
        return switch (st) {
            case IMPULSE_VM_OK -> "IMPULSE_VM_OK";
            case IMPULSE_VM_ERR_INVALID_OPCODE -> "IMPULSE_VM_ERR_INVALID_OPCODE";
            case IMPULSE_VM_ERR_OUT_OF_BOUNDS -> "IMPULSE_VM_ERR_OUT_OF_BOUNDS";
            case IMPULSE_VM_ERR_NULL_SNAPSHOT -> "IMPULSE_VM_ERR_NULL_SNAPSHOT";
            case IMPULSE_VM_ERR_STACK_OVERFLOW -> "IMPULSE_VM_ERR_STACK_OVERFLOW";
            case IMPULSE_VM_ERR_STACK_UNDERFLOW -> "IMPULSE_VM_ERR_STACK_UNDERFLOW";
            case IMPULSE_VM_ERR_INVALID_OPERAND -> "IMPULSE_VM_ERR_INVALID_OPERAND";
            case IMPULSE_VM_ERR_USER_THROW -> "IMPULSE_VM_ERR_USER_THROW";
            case IMPULSE_VM_ERR_ASSERTION_FAILED -> "IMPULSE_VM_ERR_ASSERTION_FAILED";
            case IMPULSE_VM_ERR_TRAP -> "IMPULSE_VM_ERR_TRAP";
            case IMPULSE_VM_ERR_DOMAIN_MISMATCH -> "IMPULSE_VM_ERR_DOMAIN_MISMATCH";
            case IMPULSE_VM_ERR_BUFFER_OVERFLOW -> "IMPULSE_VM_ERR_BUFFER_OVERFLOW";
            case IMPULSE_VM_ERR_FLOATING_POINT -> "IMPULSE_VM_ERR_FLOATING_POINT";
            case IMPULSE_VM_ERR_GAS_EXHAUSTED -> "IMPULSE_VM_ERR_GAS_EXHAUSTED";
            case IMPULSE_VM_ERR_TYPE_MISMATCH -> "IMPULSE_VM_ERR_TYPE_MISMATCH";
            case IMPULSE_VM_ERR_VALIDATION_FAILED -> "IMPULSE_VM_ERR_VALIDATION_FAILED";
            default -> "IMPULSE_VM_ERR_UNKNOWN_" + st;
        };
    }

    private static String formatResultJson(V11Workspace ws, V11ContextHash.Digest digest) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        sb.append("\"status\":\"").append(statusName(ws.status)).append("\",");
        sb.append("\"status_code\":").append(ws.status).append(',');
        sb.append("\"user_throw_code\":").append(ws.userThrowCode & 0xFFFFFFFFL).append(',');
        sb.append("\"pc\":").append(ws.pc).append(',');
        sb.append("\"max_dop\":").append(ws.maxDop).append(',');
        sb.append("\"flags\":{\"zf\":").append(ws.zf)
                .append(",\"st\":").append(ws.st)
                .append(",\"nf\":").append(ws.nf)
                .append(",\"raw\":").append(ws.vmFlagsRaw()).append("},");
        sb.append("\"logical_hash\":\"").append(digest.logicalHex()).append("\",");
        sb.append("\"physical_hash\":\"").append(digest.physicalHex()).append("\",");
        sb.append("\"r_valid_mask\":").append(ws.rValidMask & 0xFFFF).append(',');
        sb.append("\"r_type_word\":\"").append(String.format("0x%016x", ws.computeCanonicalRTypeWord())).append("\",");

        sb.append("\"r_regs\":{");
        for (int i = 0; i < 16; i++) {
            if (i > 0) sb.append(',');
            sb.append("\"R").append(i).append("\":");
            if (!ws.isRValid(i)) {
                sb.append("null");
            } else if (ws.readRType(i) == SCALAR_STR_HANDLE) {
                String s = ws.resolveScalarStr(ws.rRegs[i]);
                appendJsonString(sb, s != null ? s : "");
            } else {
                sb.append(ws.rRegs[i]);
            }
        }
        sb.append("},\"r_types\":{");
        for (int i = 0; i < 16; i++) {
            if (i > 0) sb.append(',');
            sb.append("\"R").append(i).append("\":").append(ws.isRValid(i) ? ws.readRType(i) : 0);
        }
        sb.append("},\"f_regs\":{");
        for (int i = 0; i < 16; i++) {
            if (i > 0) sb.append(',');
            sb.append("\"F").append(i).append("\":");
            if (!ws.isFInitialized(i)) {
                sb.append("null");
            } else {
                V11Frontier f = ws.fRegs[i];
                sb.append("{\"domain_id\":").append(f.domainId)
                        .append(",\"tier\":").append(f.tier)
                        .append(",\"nodes\":[");
                if (!f.multiLane) {
                    int[] nodes = f.toSortedArray();
                    for (int k = 0; k < nodes.length; k++) {
                        if (k > 0) sb.append(',');
                        sb.append(nodes[k] & 0xFFFFFFFFL);
                    }
                } else {
                    long[] ns = f.mlSortedNodes();
                    long[] ms = f.mlSortedMasks();
                    for (int k = 0; k < ns.length; k++) {
                        if (k > 0) sb.append(',');
                        sb.append('"').append(ns[k]).append(":0x").append(Long.toHexString(ms[k])).append('"');
                    }
                }
                sb.append("]}");
            }
        }
        sb.append("},\"outputs\":{");
        int[] slots = ws.sortedOutputSlots();
        for (int idx = 0; idx < slots.length; idx++) {
            if (idx > 0) sb.append(',');
            sb.append('"').append(slots[idx]).append("\":");
            appendVecJson(sb, ws.outputs.get(slots[idx]));
        }
        sb.append("}}");
        return sb.toString();
    }

    private static void appendVecJson(StringBuilder sb, V11Vector v) {
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            switch (v.elemTag) {
                case V11Vector.TAG_I64, V11Vector.TAG_U64 -> {
                    if (!v.isValidAt(i)) sb.append("null"); else sb.append(v.i64Data[i]);
                }
                case V11Vector.TAG_F64 -> {
                    if (!v.isValidAt(i)) sb.append("null");
                    else {
                        double f = v.f64Data[i];
                        if (Double.isNaN(f) || Double.isInfinite(f)) sb.append("null");
                        else sb.append(f);
                    }
                }
                case V11Vector.TAG_STR -> {
                    if (v.strData[i] == null) sb.append("null");
                    else appendJsonString(sb, v.strData[i]);
                }
                case V11Vector.TAG_BOOL_3VL -> {
                    if (!v.isValidAt(i)) sb.append("null");
                    else sb.append(v.boolDataAt(i));
                }
                default -> sb.append("null");
            }
        }
        sb.append(']');
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // --- Minimal zero-dependency JSON parser ---

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (o instanceof List<?> l) ? (List<Object>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
    }

    private static String asStr(Object o) {
        return (o instanceof String s) ? s : "";
    }

    private static int asInt(Object o) {
        if (o instanceof Long l) return l.intValue();
        if (o instanceof Double d) return d.intValue();
        return 0;
    }

    private static long asLong(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Double d) return d.longValue();
        if (o instanceof String s) return parseHexOrDecU64(s);
        return 0L;
    }

    private static double asDouble(Object o) {
        if (o instanceof Double d) return d;
        if (o instanceof Long l) return l.doubleValue();
        if (o instanceof String s) {
            return switch (s) {
                case "inf", "+inf", "Inf" -> Double.POSITIVE_INFINITY;
                case "-inf", "-Inf" -> Double.NEGATIVE_INFINITY;
                case "nan", "NaN" -> Double.NaN;
                default -> Double.parseDouble(s);
            };
        }
        return 0.0;
    }

    private static long parseHexOrDecU64(String raw) {
        String s = raw.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Long.parseUnsignedLong(s.substring(2), 16);
        }
        if (s.startsWith("-0x") || s.startsWith("-0X")) {
            return -Long.parseUnsignedLong(s.substring(3), 16);
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return Long.parseUnsignedLong(s);
        }
    }

    private static final class MiniJsonParser {
        private final String src;
        private int pos;

        MiniJsonParser(String src) {
            this.src = src;
        }

        private void skipWs() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object parseValue() {
            skipWs();
            if (pos >= src.length()) throw new IllegalArgumentException("Unexpected EOF");
            char c = src.charAt(pos);
            return switch (c) {
                case '"' -> parseString();
                case '{' -> parseObject();
                case '[' -> parseArray();
                case 't' -> { pos += 4; yield Boolean.TRUE; }
                case 'f' -> { pos += 5; yield Boolean.FALSE; }
                case 'n' -> { pos += 4; yield null; }
                default -> parseNumber();
            };
        }

        private String parseString() {
            pos++; // skip '"'
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            int code = Integer.parseInt(src.substring(pos, pos + 4), 16);
                            sb.append((char) code);
                            pos += 4;
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private Map<String, Object> parseObject() {
            pos++; // skip '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (pos < src.length() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String k = parseString();
                skipWs();
                if (src.charAt(pos++) != ':') throw new IllegalArgumentException("Expected ':'");
                Object v = parseValue();
                map.put(k, v);
                skipWs();
                char next = src.charAt(pos++);
                if (next == '}') return map;
                if (next != ',') throw new IllegalArgumentException("Expected ',' or '}'");
            }
        }

        private List<Object> parseArray() {
            pos++; // skip '['
            List<Object> list = new ArrayList<>();
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                char next = src.charAt(pos++);
                if (next == ']') return list;
                if (next != ',') throw new IllegalArgumentException("Expected ',' or ']'");
            }
        }

        private Object parseNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') pos++;
            boolean isFloat = false;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    if (c == '.' || c == 'e' || c == 'E') isFloat = true;
                    pos++;
                } else {
                    break;
                }
            }
            String tok = src.substring(start, pos);
            if (isFloat) {
                return Double.parseDouble(tok);
            }
            try {
                return Long.parseLong(tok);
            } catch (NumberFormatException e) {
                try {
                    return Long.parseUnsignedLong(tok);
                } catch (NumberFormatException e2) {
                    return Double.parseDouble(tok);
                }
            }
        }
    }
}
