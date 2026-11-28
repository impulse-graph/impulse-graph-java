package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.api.meta.AttributeMeta;
import org.impulsegraph.api.meta.DomainMeta;
import org.impulsegraph.compiler.emitter.ProgramBuilder;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.impulsegraph.vm.ImpulseIsaTables.*;

/**
 * Self-describing, metadata-driven Java 25 v0.11 Storage Coverage, Multi-Lane
 * vs Single-Lane, Morsel, and Attribute-Type Benchmark & Equivalence Runner
 * (exact counterpart of C++ {@code bench_storage_coverage.cpp}).
 */
public final class StorageCoverageBenchRunner {

	private record RunRes(int status, double us, long val, double fval, boolean hasFval) {
	}

	private static RunRes runTimed(long[] words, ImpulseInterpreter.SnapshotBinding binding, ImpulseWorkspace ws,
			ImpulseWorkspace.ParamEntry param0, int warmup, int iters) {
		for (int i = 0; i < warmup; i++) {
			ws.params.clear();
			if (param0 != null)
				ws.params.put(0, param0);
			ImpulseInterpreter.execute(words, binding, ws);
			if (ws.status != IMPULSE_VM_OK) {
				return new RunRes(ws.status, 0.0, 0L, 0.0, false);
			}
		}
		double[] samples = new double[iters];
		for (int i = 0; i < iters; i++) {
			ws.params.clear();
			if (param0 != null)
				ws.params.put(0, param0);
			long t0 = System.nanoTime();
			ImpulseInterpreter.execute(words, binding, ws);
			long t1 = System.nanoTime();
			if (ws.status != IMPULSE_VM_OK) {
				return new RunRes(ws.status, 0.0, 0L, 0.0, false);
			}
			samples[i] = (t1 - t0) / 1000.0;
		}
		Arrays.sort(samples);
		double med = samples.length == 0 ? 0.0 : samples[samples.length / 2];
		long v = 0L;
		double fv = 0.0;
		boolean hasF = false;
		ImpulseVector ov = ws.outputs.get(0);
		if (ov != null && ov.length > 0) {
			if ((ov.elemTag == ImpulseVector.TAG_I64 || ov.elemTag == ImpulseVector.TAG_U64) && ov.i64Data != null) {
				v = ov.i64Data[0];
			} else if (ov.elemTag == ImpulseVector.TAG_F64 && ov.f64Data != null) {
				fv = ov.f64Data[0];
				hasF = true;
			}
		}
		return new RunRes(IMPULSE_VM_OK, med, v, fv, hasF);
	}

	private static long[] buildWalk1Hop(int relId, int inDom, int subop) {
		ProgramBuilder b = new ProgramBuilder();
		b.emitU16(OP_LOAD_PARAM, SUBOP_LOAD_PARAM_FRONTIER, 0, 0, 0, 0, 0, 0, inDom);
		b.emitU16(OP_WALK, subop, 0, 1, 0, 0, 0, relId, 0);
		b.emit(OP_SET_CARDINALITY, SUBOP_SET_CARDINALITY_CARD_1BIT, 0, 0, 1, 0, 0, 0);
		b.emitU16(OP_COLLECT, SUBOP_COLLECT_SCALAR, 0, 0, 0, 0, 0, 0, 0);
		b.emit(OP_HALT, 0, 0, 0, 0, 0, 0, 0);
		return b.toWordArray();
	}

	private static long[] buildWalk2Hop(int relId, int inDom, int subop) {
		ProgramBuilder b = new ProgramBuilder();
		b.emitU16(OP_LOAD_PARAM, SUBOP_LOAD_PARAM_FRONTIER, 0, 0, 0, 0, 0, 0, inDom);
		b.emitU16(OP_WALK, subop, 0, 1, 0, 0, 0, relId, 0);
		b.emitU16(OP_WALK, subop, 0, 0, 1, 0, 0, relId, 0);
		b.emit(OP_SET_CARDINALITY, SUBOP_SET_CARDINALITY_CARD_1BIT, 0, 0, 0, 0, 0, 0);
		b.emitU16(OP_COLLECT, SUBOP_COLLECT_SCALAR, 0, 0, 0, 0, 0, 0, 0);
		b.emit(OP_HALT, 0, 0, 0, 0, 0, 0, 0);
		return b.toWordArray();
	}

	private static long[] buildMorsel1Hop(int relId, int inDom, int tgtDom, int subop) {
		ProgramBuilder b = new ProgramBuilder();
		b.emitU16(OP_LOAD_PARAM, SUBOP_LOAD_PARAM_FRONTIER, 0, 0, 0, 0, 0, 0, inDom);
		b.emitU16(OP_WALK_MORSEL, subop, 0, 0, 0, 0, 0, relId, 2);
		b.emitU16(OP_MORSEL_COMMIT, SUBOP_MORSEL_COMMIT_YIELD_FRONTIER, 0, 1, 9, 0, 0, tgtDom, 0);
		b.emitU16(OP_MORSEL_END, 0, 0, 0, 0, 0, 0, 0, 2);
		b.emit(OP_SET_CARDINALITY, SUBOP_SET_CARDINALITY_CARD_1BIT, 0, 0, 1, 0, 0, 0);
		b.emitU16(OP_COLLECT, SUBOP_COLLECT_SCALAR, 0, 0, 0, 0, 0, 0, 0);
		b.emit(OP_HALT, 0, 0, 0, 0, 0, 0, 0);
		return b.toWordArray();
	}

	private static long pow10(int s) {
		long r = 1L;
		for (int i = 0; i < s; i++)
			r *= 10L;
		return r;
	}

	private static long[] buildNodeAttrEq40(int domId, int attrIdx, boolean isFloat, long targetI64) {
		ProgramBuilder b = new ProgramBuilder();
		b.emitU16(OP_LOAD_COLUMN, 0, 0, 0, 0, 0, 0, domId, attrIdx);
		if (isFloat) {
			b.emit(OP_LOAD_CONST, SUBOP_LOAD_CONST_F32, 0, 1, 0, 0, 0, Float.floatToRawIntBits(40.0f));
		} else {
			b.emitLoadConstI64(1, targetI64);
		}
		b.emit(OP_VEC_CMP, SUBOP_VEC_CMP_EQ, FLAG_SCALAR_RHS, 1, 0, 1, 0, 0);
		b.emitU16(OP_SET_CONVERT, 2, 0, 0, 1, 0, 0, domId, 0);
		b.emit(OP_SET_CARDINALITY, SUBOP_SET_CARDINALITY_CARD_1BIT, 0, 0, 0, 0, 0, 0);
		b.emitU16(OP_COLLECT, SUBOP_COLLECT_SCALAR, 0, 0, 0, 0, 0, 0, 0);
		b.emit(OP_HALT, 0, 0, 0, 0, 0, 0, 0);
		return b.toWordArray();
	}

	private static long[] buildEdgeAttrMorselEq40(int relId, int inDom, int attrIdx, boolean isFloat, long targetI64) {
		ProgramBuilder b = new ProgramBuilder();
		b.emitU16(OP_LOAD_PARAM, SUBOP_LOAD_PARAM_FRONTIER, 0, 0, 0, 0, 0, 0, inDom);
		b.emitU16(OP_WALK, SUBOP_WALK_FWD, 0, 1, 0, 0, 0, relId, 0);
		if (isFloat) {
			b.emit(OP_LOAD_CONST, SUBOP_LOAD_CONST_F32, 0, 1, 0, 0, 0, Float.floatToRawIntBits(40.0f));
		} else {
			b.emitLoadConstI64(1, targetI64);
		}
		b.emit(OP_LOAD_CONST, SUBOP_LOAD_CONST_I32, 0, 0, 0, 0, 0, 0);
		b.emitU16(OP_WALK_MORSEL, SUBOP_WALK_MORSEL_FWD, 0, 0, 1, 0, 0, relId, 5);
		b.emitU16(OP_GATHER_EDGE_ATTR, SUBOP_GATHER_EDGE_ATTR_VEC, 0, 12, 10, 0, 0, relId, attrIdx);
		b.emit(OP_VEC_CMP, SUBOP_VEC_CMP_EQ, FLAG_SCALAR_RHS, 13, 12, 1, 0, 0);
		b.emitU16(OP_SET_CONVERT, 2, 0, 9, 13, 0, 0, 0xFFFF, 0);
		b.emitU16(OP_MORSEL_COMMIT, SUBOP_MORSEL_COMMIT_COUNT_ADD_R, FLAG_MASKED_S3, 0, 0, 0, 9, 0, 0);
		b.emitU16(OP_MORSEL_END, 0, 0, 0, 0, 0, 0, 0, 5);
		b.emitU16(OP_COLLECT, SUBOP_COLLECT_SCALAR, 0, 0, 0, 0, 0, 0, 0);
		b.emit(OP_HALT, 0, 0, 0, 0, 0, 0, 0);
		return b.toWordArray();
	}

	private static long[] buildNodeAttrReduce(int domId, int attrIdx, int redSubop) {
		ProgramBuilder b = new ProgramBuilder();
		b.emitU16(OP_LOAD_COLUMN, 0, 0, 0, 0, 0, 0, domId, attrIdx);
		b.emit(OP_VEC_REDUCE, redSubop, 0, 0, 0, 0, 0, 0);
		b.emitU16(OP_COLLECT, SUBOP_COLLECT_SCALAR, 0, 0, 0, 0, 0, 0, 0);
		b.emit(OP_HALT, 0, 0, 0, 0, 0, 0, 0);
		return b.toWordArray();
	}

	private static long[] buildMl2Hop(int relId, int domId, boolean forceW64) {
		ProgramBuilder b = new ProgramBuilder();
		b.emitU16(OP_LOAD_PARAM, SUBOP_LOAD_PARAM_VEC, 0, 0, 0, 0, 0, 0, 2);
		b.emitU16(OP_LANE_INIT_SEEDS, forceW64 ? SUBOP_LANE_INIT_SEEDS_FORCE_W64 : SUBOP_LANE_INIT_SEEDS_AUTO_WIDTH, 0,
				0, 0, 0, 0, domId, 0);
		b.emitU16(OP_LANE_WALK, SUBOP_LANE_WALK_FWD, 0, 1, 0, 0, 0, relId, 0);
		b.emitU16(OP_LANE_WALK, SUBOP_LANE_WALK_FWD, 0, 0, 1, 0, 0, relId, 0);
		b.emit(OP_SET_CARDINALITY, SUBOP_SET_CARDINALITY_LANE_POP_SUM, 0, 0, 0, 0, 0, 0);
		b.emitU16(OP_COLLECT, SUBOP_COLLECT_SCALAR, 0, 0, 0, 0, 0, 0, 0);
		b.emit(OP_HALT, 0, 0, 0, 0, 0, 0, 0);
		return b.toWordArray();
	}

	private static long[] buildMlMorsel2Hop(int relId, int domId, boolean forceW64) {
		ProgramBuilder b = new ProgramBuilder();
		b.emitU16(OP_LOAD_PARAM, SUBOP_LOAD_PARAM_VEC, 0, 0, 0, 0, 0, 0, 2);
		b.emitU16(OP_LANE_INIT_SEEDS, forceW64 ? SUBOP_LANE_INIT_SEEDS_FORCE_W64 : SUBOP_LANE_INIT_SEEDS_AUTO_WIDTH, 0,
				0, 0, 0, 0, domId, 0);
		b.emitU16(OP_LANE_WALK, SUBOP_LANE_WALK_FWD, 0, 1, 0, 0, 0, relId, 0);
		b.emitU16(OP_WALK_MORSEL, SUBOP_WALK_MORSEL_FWD, 0, 0, 1, 0, 0, relId, 2);
		b.emitU16(OP_MORSEL_COMMIT, SUBOP_MORSEL_COMMIT_YIELD_LANE_OR, 0, 2, 9, 11, 0, domId, 0);
		b.emitU16(OP_MORSEL_END, 0, 0, 0, 0, 0, 0, 0, 2);
		b.emit(OP_SET_CARDINALITY, SUBOP_SET_CARDINALITY_LANE_POP_SUM, 0, 0, 2, 0, 0, 0);
		b.emitU16(OP_COLLECT, SUBOP_COLLECT_SCALAR, 0, 0, 0, 0, 0, 0, 0);
		b.emit(OP_HALT, 0, 0, 0, 0, 0, 0, 0);
		return b.toWordArray();
	}

	private static String fmtF(double v) {
		if (Double.isNaN(v))
			return "\"NaN\"";
		if (Double.isInfinite(v))
			return v < 0 ? "\"-Inf\"" : "\"Inf\"";
		return String.format(Locale.ROOT, "%.6g", v);
	}

	private static ImpulseVector vecU64(long[] vals) {
		int words = Math.max(1, (vals.length + 63) >>> 6);
		long[] valid = new long[words];
		Arrays.fill(valid, -1L);
		if ((vals.length & 63) != 0)
			valid[words - 1] = (1L << (vals.length & 63)) - 1L;
		return ImpulseVector.ofU64(vals, valid);
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("Usage: StorageCoverageBenchRunner <storage_coverage.imps> [warmup=50] [iters=50]");
			System.exit(1);
		}
		String path = args[0];
		int warmup = args.length > 1 ? Integer.parseInt(args[1]) : 50;
		int iters = args.length > 2 ? Integer.parseInt(args[2]) : 50;

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(Path.of(path), arena);
			ImpulseGraphSnapshot snap = loaded.graph();
			ImpulseSnapshotView view = ImpulseSnapshotAdapter.from(snap);

			int[] domCards = new int[view.domainCards.length];
			int maxCard = 64;
			for (int i = 0; i < domCards.length; i++) {
				domCards[i] = (int) view.domainCards[i];
				if (domCards[i] > maxCard)
					maxCard = domCards[i];
			}
			ImpulseInterpreter.SnapshotBinding binding = new ImpulseInterpreter.SnapshotBinding(domCards, view);
			ImpulseWorkspace ws = new ImpulseWorkspace(maxCard);

			String canonDomName = snap.getMetadata("coverage.canonical_dom");
			if (canonDomName == null || canonDomName.isEmpty())
				canonDomName = "EQ_DOM_U32_I32";
			String canonRelName = snap.getMetadata("coverage.canonical_rel");
			if (canonRelName == null || canonRelName.isEmpty())
				canonRelName = "EQ_REL_ALL3_U32";
			String seedCsv = snap.getMetadata("coverage.seed_nodes");
			if (seedCsv == null || seedCsv.isEmpty())
				seedCsv = "1,2,5,10,40,63,64,65";
			String[] seedToks = seedCsv.split(",");
			long[] seeds = new long[seedToks.length];
			for (int i = 0; i < seedToks.length; i++)
				seeds[i] = Long.parseLong(seedToks[i].trim());
			long mainSeed = seeds.length > 4 ? seeds[4] : seeds[0];

			int canonDomId = 0;
			for (int d = 0; d < domCards.length; d++) {
				if (canonDomName.equals(snap.getDomainName(d))) {
					canonDomId = d;
					break;
				}
			}
			int canonRelId = 0;
			String[] relNames = new String[view.relations.length];
			for (String rn : snap.getRelationNames()) {
				RelationSnapshot rs = snap.getRelationSnapshot(rn);
				if (rs != null && rs.getRelationId() >= 0 && rs.getRelationId() < relNames.length) {
					relNames[rs.getRelationId()] = rn;
					if (canonRelName.equals(rn))
						canonRelId = rs.getRelationId();
				}
			}

			StringBuilder sb = new StringBuilder(32768);
			sb.append(String.format(Locale.ROOT,
					"{\"engine\":\"java-v11\",\"snapshot\":\"%s\",\"domains\":%d,\"relations\":%d,\"topology\":[", path,
					domCards.length, view.relations.length));

			boolean firstRel = true;
			for (int r = 0; r < view.relations.length; r++) {
				String rname = relNames[r];
				if (rname == null || !rname.startsWith("EQ_REL_"))
					continue;
				ImpulseSnapshotView.RelView rv = view.relations[r];
				boolean hasCsr = rv.hasCsrSegments();
				boolean hasCsc = rv.hasCscSegments();
				boolean hasCoo = rv.hasCooSegments();

				ImpulseWorkspace.ParamFrontier pfSrc = new ImpulseWorkspace.ParamFrontier(rv.srcDom,
						new long[]{mainSeed});
				ImpulseWorkspace.ParamFrontier pfTgt = new ImpulseWorkspace.ParamFrontier(rv.tgtDom,
						new long[]{mainSeed});

				RunRes rCsr = runTimed(buildWalk1Hop(r, rv.srcDom, SUBOP_WALK_FWD), binding, ws, pfSrc, warmup, iters);
				RunRes rCsc = runTimed(buildWalk1Hop(r, rv.tgtDom, SUBOP_WALK_REV), binding, ws, pfTgt, warmup, iters);
				RunRes rCooF = runTimed(buildWalk1Hop(r, rv.srcDom, SUBOP_WALK_COO_FWD), binding, ws, pfSrc, warmup,
						iters);
				RunRes rCooR = runTimed(buildWalk1Hop(r, rv.tgtDom, SUBOP_WALK_COO_REV), binding, ws, pfTgt, warmup,
						iters);
				RunRes rMors = hasCsr
						? runTimed(buildMorsel1Hop(r, rv.srcDom, rv.tgtDom, SUBOP_WALK_MORSEL_FWD), binding, ws, pfSrc,
								warmup, iters)
						: new RunRes(IMPULSE_VM_ERR_INVALID_OPERAND, 0.0, -1L, 0.0, false);

				if (!firstRel)
					sb.append(',');
				firstRel = false;
				sb.append(String.format(Locale.ROOT,
						"{\"name\":\"%s\",\"rel_id\":%d,\"src_dom\":%d,\"tgt_dom\":%d,\"has_csr\":%s,\"has_csc\":%s,\"has_coo\":%s,"
								+ "\"csr\":{\"st\":%d,\"us\":%.2f,\"card\":%d},\"csc\":{\"st\":%d,\"us\":%.2f,\"card\":%d},"
								+ "\"coo_fwd\":{\"st\":%d,\"us\":%.2f,\"card\":%d},\"coo_rev\":{\"st\":%d,\"us\":%.2f,\"card\":%d},"
								+ "\"morsel_fwd\":{\"st\":%d,\"us\":%.2f,\"card\":%d}}",
						rname, r, rv.srcDom, rv.tgtDom, hasCsr, hasCsc, hasCoo, rCsr.status, rCsr.us, rCsr.val,
						rCsc.status, rCsc.us, rCsc.val, rCooF.status, rCooF.us, rCooF.val, rCooR.status, rCooR.us,
						rCooR.val, rMors.status, rMors.us, rMors.val));
			}

			// Attribute sweep
			sb.append("],\"attrs\":[");
			boolean firstAttr = true;
			List<String> domAttrNames = snap.getDomainAttributeNames(canonDomName);
			DomainMeta canonDomMeta = snap.getDomainMeta(canonDomName);
			List<AttributeMeta> domAttrMetas = canonDomMeta != null ? canonDomMeta.attributes() : List.of();
			for (int a = 0; a < domAttrNames.size(); a++) {
				String aname = domAttrNames.get(a);
				if (!aname.startsWith("EQ_NUM_"))
					continue;
				boolean isFlt = aname.contains("_F32") || aname.contains("_F64");
				long targetI64 = 40L;
				if (a < domAttrMetas.size() && (domAttrMetas.get(a).baseTypeCode() & 0xFF) == 0x15) {
					targetI64 = 40L * pow10(domAttrMetas.get(a).scale());
				}
				RunRes rr = runTimed(buildNodeAttrEq40(canonDomId, a, isFlt, targetI64), binding, ws, null, warmup,
						iters);
				if (!firstAttr)
					sb.append(',');
				firstAttr = false;
				sb.append(String.format(Locale.ROOT,
						"{\"scope\":\"node\",\"name\":\"%s\",\"idx\":%d,\"st\":%d,\"us\":%.2f,\"count\":%d}", aname, a,
						rr.status, rr.us, rr.val));
			}

			RelationSnapshot canonRs = snap.getRelationSnapshot(canonRelName);
			ImpulseSnapshotView.RelView canonRv = view.relations[canonRelId];
			if (canonRs instanceof org.impulsegraph.storage.csr.RelationSnapshot crs) {
				int edgeAttrCount = crs.getAttributeSegments().size();
				List<AttributeMeta> edgeAttrMetas = crs.getAttributeMetas();
				ImpulseWorkspace.ParamFrontier pfSrc = new ImpulseWorkspace.ParamFrontier(canonRv.srcDom,
						new long[]{mainSeed});
				for (int a = 0; a < edgeAttrCount; a++) {
					String aname = crs.getAttributeName(a);
					if (aname == null || !aname.startsWith("EQ_NUM_"))
						continue;
					boolean isFlt = aname.contains("_F32") || aname.contains("_F64");
					long targetI64 = 40L;
					if (a < edgeAttrMetas.size() && (edgeAttrMetas.get(a).baseTypeCode() & 0xFF) == 0x15) {
						targetI64 = 40L * pow10(edgeAttrMetas.get(a).scale());
					}
					RunRes rr = runTimed(buildEdgeAttrMorselEq40(canonRelId, canonRv.srcDom, a, isFlt, targetI64),
							binding, ws, pfSrc, warmup, iters);
					if (!firstAttr)
						sb.append(',');
					firstAttr = false;
					sb.append(String.format(Locale.ROOT,
							"{\"scope\":\"edge_morsel\",\"name\":\"%s\",\"idx\":%d,\"st\":%d,\"us\":%.2f,\"count\":%d}",
							aname, a, rr.status, rr.us, rr.val));
				}
			}

			// Boundary checks
			sb.append("],\"boundaries\":[");
			boolean firstB = true;
			for (int a = 0; a < domAttrNames.size(); a++) {
				String aname = domAttrNames.get(a);
				if (!aname.startsWith("BOUNDARY_"))
					continue;
				RunRes rCnt = runTimed(buildNodeAttrReduce(canonDomId, a, SUBOP_VEC_REDUCE_COUNT_VALID), binding, ws,
						null, 5, 5);
				RunRes rMin = runTimed(buildNodeAttrReduce(canonDomId, a, SUBOP_VEC_REDUCE_MIN), binding, ws, null, 5,
						5);
				RunRes rMax = runTimed(buildNodeAttrReduce(canonDomId, a, SUBOP_VEC_REDUCE_MAX), binding, ws, null, 5,
						5);
				if (!firstB)
					sb.append(',');
				firstB = false;
				if (rMin.hasFval) {
					sb.append(String.format(Locale.ROOT, "{\"name\":\"%s\",\"count_valid\":%d,\"min\":%s,\"max\":%s}",
							aname, rCnt.val, fmtF(rMin.fval), fmtF(rMax.fval)));
				} else {
					sb.append(String.format(Locale.ROOT, "{\"name\":\"%s\",\"count_valid\":%d,\"min\":%d,\"max\":%d}",
							aname, rCnt.val, rMin.val, rMax.val));
				}
			}

			// Multi-lane vs Single-lane sweep
			sb.append("],\"multilane\":[");
			boolean[] firstMl = {true};
			java.util.function.BiConsumer<String, Object[]> emitMl = (label, arr) -> {
				int k = (Integer) arr[0];
				RunRes rr = (RunRes) arr[1];
				if (!firstMl[0])
					sb.append(',');
				firstMl[0] = false;
				sb.append(String.format(Locale.ROOT,
						"{\"label\":\"%s\",\"k\":%d,\"st\":%d,\"us\":%.2f,\"us_per_lane\":%.2f,\"pop_sum\":%d}", label,
						k, rr.status, rr.us, (k > 0 ? rr.us / k : rr.us), rr.val));
			};

			long[] walk2 = buildWalk2Hop(canonRelId, canonRv.srcDom, SUBOP_WALK_FWD);
			emitMl.accept("single_1seed_2hop", new Object[]{1, runTimed(walk2, binding, ws,
					new ImpulseWorkspace.ParamFrontier(canonRv.srcDom, new long[]{seeds[0]}), warmup, iters)});

			int[] ks = {1, 8, 16, 32, 64};
			for (int k : ks) {
				if (k > seeds.length)
					continue;
				ImpulseWorkspace.ParamVec sv = new ImpulseWorkspace.ParamVec(vecU64(Arrays.copyOf(seeds, k)));
				emitMl.accept("ml_k" + k + "_auto_2hop", new Object[]{k,
						runTimed(buildMl2Hop(canonRelId, canonRv.srcDom, false), binding, ws, sv, warmup, iters)});
				if (k == 1) {
					emitMl.accept("ml_k1_force_w64_2hop", new Object[]{1,
							runTimed(buildMl2Hop(canonRelId, canonRv.srcDom, true), binding, ws, sv, warmup, iters)});
				}
				emitMl.accept("ml_k" + k + "_morsel_2hop",
						new Object[]{k, runTimed(buildMlMorsel2Hop(canonRelId, canonRv.srcDom, false), binding, ws, sv,
								warmup, iters)});
			}

			int kRef = Math.min(64, seeds.length);
			long totalCard = 0L;
			double totalUs = 0.0;
			for (int i = 0; i < kRef; i++) {
				RunRes rr = runTimed(walk2, binding, ws,
						new ImpulseWorkspace.ParamFrontier(canonRv.srcDom, new long[]{seeds[i]}), 5, 10);
				totalCard += rr.val;
				totalUs += rr.us;
			}
			emitMl.accept("single_loop_64x_2hop",
					new Object[]{kRef, new RunRes(IMPULSE_VM_OK, totalUs, totalCard, 0.0, false)});

			sb.append("]}");
			System.out.println(sb);
		}
	}
}
