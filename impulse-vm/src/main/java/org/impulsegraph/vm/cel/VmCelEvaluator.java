package org.impulsegraph.vm.cel;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedAttribute;
import org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedDomain;
import org.impulsegraph.storage.csr.RelationSnapshot;

/**
 * Zero-dependency Spec v0.10.0 CEL compiler and Kleene 3-valued logic (3VL)
 * evaluator operating directly over off-heap {@link LoadedDomain} and
 * {@link RelationSnapshot} columnar segments.
 */
public final class VmCelEvaluator {

	private static final ConcurrentHashMap<String, ExprNode> PARSE_CACHE = new ConcurrentHashMap<>();
	private static final ValueLayout.OfLong BE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

	public static final byte KIND_NULL = 0;
	public static final byte KIND_BOOL = 1;
	public static final byte KIND_INT = 2;
	public static final byte KIND_UINT = 3;
	public static final byte KIND_FLOAT = 4;
	public static final byte KIND_STR = 5;

	public static final byte TRI_FALSE = 0;
	public static final byte TRI_TRUE = 1;
	public static final byte TRI_NULL = 2;

	private final ExprNode root;
	private final Map<String, Object> params;
	private final int maxDepth;

	private VmCelEvaluator(ExprNode root, Map<String, Object> params) {
		this.root = root;
		this.params = params != null ? params : Map.of();
		this.maxDepth = computeDepth(root) + 4;
	}

	public static VmCelEvaluator compile(String celExpr, Map<String, Object> params) {
		if (celExpr == null || celExpr.isBlank()) {
			return new VmCelEvaluator(new BoolLit(true), params);
		}
		ExprNode ast = PARSE_CACHE.computeIfAbsent(celExpr.trim(), VmCelEvaluator::parseExpression);
		return new VmCelEvaluator(ast, params);
	}

	public EvalScratch createScratch() {
		return new EvalScratch(maxDepth);
	}

	public boolean evalDomainFilter(LoadedDomain domain, int denseNodeId, EvalScratch scratch) {
		scratch.bindDomain(domain, denseNodeId, params);
		evalInto(root, scratch, 0);
		return toTriBool(scratch, 0) == TRI_TRUE;
	}

	public boolean evalEdgeFilter(RelationSnapshot relation, int edgeIdx, LoadedDomain srcDomain, int srcNodeId,
			LoadedDomain tgtDomain, int tgtNodeId, EvalScratch scratch) {
		scratch.bindEdge(relation, edgeIdx, srcDomain, srcNodeId, tgtDomain, tgtNodeId, params);
		evalInto(root, scratch, 0);
		return toTriBool(scratch, 0) == TRI_TRUE;
	}

	/**
	 * Evaluates a scalar numeric attribute or CEL arithmetic expression on
	 * {@code denseNodeId} in {@code domain}. Returns {@link Double#NaN} when the
	 * evaluated value is NULL under Kleene 3VL so reductions skip NULLs cleanly.
	 */
	public double evalDomainScalarOrNaN(LoadedDomain domain, int denseNodeId, EvalScratch scratch) {
		scratch.bindDomain(domain, denseNodeId, params);
		evalInto(root, scratch, 0);
		return switch (scratch.kinds[0]) {
			case KIND_INT -> (double) scratch.iVals[0];
			case KIND_UINT -> uint64ToDouble(scratch.iVals[0]);
			case KIND_FLOAT -> scratch.fVals[0];
			case KIND_BOOL -> scratch.iVals[0] != 0L ? 1.0 : 0.0;
			default -> Double.NaN;
		};
	}

	/**
	 * Reads a fixed-dimension vector attribute (`VEC4` / `VEC8` `float32`,
	 * `float16`, or `float64`) for {@code row} into {@code outVec[0..dim-1]}.
	 * Returns {@code false} if the attribute does not exist or {@code row} is NULL
	 * in the validity bitmap.
	 */
	public static boolean readDomainVector(LoadedDomain domain, int attrIdx, int row, float[] outVec, int dim) {
		if (domain == null || attrIdx < 0 || attrIdx >= domain.attributeCount() || row < 0
				|| (domain.nodeCount() > 0 && row >= domain.nodeCount())) {
			return false;
		}
		LoadedAttribute attr = domain.getAttribute(attrIdx);
		if (attr == null) {
			return false;
		}
		if (attr.isNullable()) {
			MemorySegment valSeg = domain.getValiditySegment(attrIdx);
			if (valSeg == null || valSeg.equals(MemorySegment.NULL) || (row >>> 3) >= valSeg.byteSize()) {
				return false;
			}
			byte b = valSeg.get(ValueLayout.JAVA_BYTE, row >>> 3);
			if (((b >>> (row & 7)) & 1) == 0) {
				return false;
			}
		}
		MemorySegment dataSeg = domain.getAttributeSegment(attrIdx);
		if (dataSeg == null || dataSeg.equals(MemorySegment.NULL)) {
			return false;
		}
		int physDim = Math.max(1, attr.dimension());
		int readDim = Math.min(dim, physDim);
		int baseCode = attr.typeCode() & 0x7F;
		long nRows = domain.nodeCount();
		long bytesPerElem = (nRows > 0 && physDim > 0) ? (dataSeg.byteSize() / (nRows * physDim)) : 0L;

		if (baseCode == 0x05 || (baseCode == 0x0E && bytesPerElem == 2L) || bytesPerElem == 2L) {
			long baseOff = (long) row * physDim * 2L;
			if (baseOff + (long) readDim * 2L > dataSeg.byteSize()) {
				return false;
			}
			for (int d = 0; d < readDim; d++) {
				short h = dataSeg.get(ValueLayout.JAVA_SHORT_UNALIGNED, baseOff + d * 2L);
				outVec[d] = Float.float16ToFloat(h);
			}
			for (int d = readDim; d < dim; d++) {
				outVec[d] = 0.0f;
			}
			return true;
		} else if (baseCode == 0x07 || bytesPerElem == 8L) {
			long baseOff = (long) row * physDim * 8L;
			if (baseOff + (long) readDim * 8L > dataSeg.byteSize()) {
				return false;
			}
			for (int d = 0; d < readDim; d++) {
				outVec[d] = (float) dataSeg.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, baseOff + d * 8L);
			}
			for (int d = readDim; d < dim; d++) {
				outVec[d] = 0.0f;
			}
			return true;
		} else {
			long baseOff = (long) row * physDim * 4L;
			if (baseOff + (long) readDim * 4L > dataSeg.byteSize()) {
				return false;
			}
			for (int d = 0; d < readDim; d++) {
				outVec[d] = dataSeg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, baseOff + d * 4L);
			}
			for (int d = readDim; d < dim; d++) {
				outVec[d] = 0.0f;
			}
			return true;
		}
	}

	public static final class EvalScratch {
		final byte[] kinds;
		final long[] iVals;
		final double[] fVals;
		final Object[] oVals;

		LoadedDomain domain;
		int nodeId;
		RelationSnapshot relation;
		int edgeIdx;
		LoadedDomain srcDomain;
		int srcNodeId;
		LoadedDomain tgtDomain;
		int tgtNodeId;
		boolean edgeMode;
		Map<String, Object> params = Map.of();

		EvalScratch(int depth) {
			int cap = Math.max(16, depth);
			this.kinds = new byte[cap];
			this.iVals = new long[cap];
			this.fVals = new double[cap];
			this.oVals = new Object[cap];
		}

		void bindDomain(LoadedDomain domain, int nodeId, Map<String, Object> params) {
			this.edgeMode = false;
			this.domain = domain;
			this.nodeId = nodeId;
			this.relation = null;
			this.params = params != null ? params : Map.of();
		}

		void bindEdge(RelationSnapshot relation, int edgeIdx, LoadedDomain srcDomain, int srcNodeId,
				LoadedDomain tgtDomain, int tgtNodeId, Map<String, Object> params) {
			this.edgeMode = true;
			this.relation = relation;
			this.edgeIdx = edgeIdx;
			this.srcDomain = srcDomain;
			this.srcNodeId = srcNodeId;
			this.tgtDomain = tgtDomain;
			this.tgtNodeId = tgtNodeId;
			this.domain = null;
			this.params = params != null ? params : Map.of();
		}

		void setNull(int sp) {
			kinds[sp] = KIND_NULL;
			iVals[sp] = 0L;
			fVals[sp] = 0.0;
			oVals[sp] = null;
		}

		void setBool(int sp, boolean v) {
			kinds[sp] = KIND_BOOL;
			iVals[sp] = v ? 1L : 0L;
			oVals[sp] = null;
		}

		void setInt(int sp, long v) {
			kinds[sp] = KIND_INT;
			iVals[sp] = v;
			oVals[sp] = null;
		}

		void setUint(int sp, long v) {
			kinds[sp] = KIND_UINT;
			iVals[sp] = v;
			oVals[sp] = null;
		}

		void setFloat(int sp, double v) {
			if (Double.isNaN(v)) {
				setNull(sp);
			} else {
				kinds[sp] = KIND_FLOAT;
				fVals[sp] = v;
				oVals[sp] = null;
			}
		}

		void setStr(int sp, String s) {
			if (s == null) {
				setNull(sp);
			} else {
				kinds[sp] = KIND_STR;
				oVals[sp] = s;
			}
		}

		void copySlot(int dst, int src) {
			kinds[dst] = kinds[src];
			iVals[dst] = iVals[src];
			fVals[dst] = fVals[src];
			oVals[dst] = oVals[src];
		}
	}

	private static byte toTriBool(EvalScratch s, int sp) {
		return switch (s.kinds[sp]) {
			case KIND_NULL -> TRI_NULL;
			case KIND_BOOL, KIND_INT, KIND_UINT -> s.iVals[sp] != 0L ? TRI_TRUE : TRI_FALSE;
			case KIND_FLOAT -> Double.isNaN(s.fVals[sp]) ? TRI_NULL : (s.fVals[sp] != 0.0 ? TRI_TRUE : TRI_FALSE);
			case KIND_STR -> ((String) s.oVals[sp]).isEmpty() ? TRI_FALSE : TRI_TRUE;
			default -> TRI_NULL;
		};
	}

	private static void setFromTriBool(EvalScratch s, int sp, byte tri) {
		if (tri == TRI_TRUE) {
			s.setBool(sp, true);
		} else if (tri == TRI_FALSE) {
			s.setBool(sp, false);
		} else {
			s.setNull(sp);
		}
	}

	private static void evalInto(ExprNode node, EvalScratch s, int sp) {
		if (node instanceof IntLit lit) {
			s.setInt(sp, lit.val);
		} else if (node instanceof UintLit lit) {
			s.setUint(sp, lit.val);
		} else if (node instanceof FloatLit lit) {
			s.setFloat(sp, lit.val);
		} else if (node instanceof BoolLit lit) {
			s.setBool(sp, lit.val);
		} else if (node instanceof StrLit lit) {
			s.setStr(sp, lit.val);
		} else if (node instanceof NullLit) {
			s.setNull(sp);
		} else if (node instanceof ParamRef pref) {
			Object v = s.params.get(pref.name);
			if (v == null && pref.name.startsWith("@")) {
				v = s.params.get(pref.name.substring(1));
			} else if (v == null) {
				v = s.params.get("@" + pref.name);
			}
			bindJavaValue(s, sp, v);
		} else if (node instanceof AttrRef ref) {
			readAttrInto(ref.qualifier, ref.name, s, sp);
		} else if (node instanceof UnaryNode un) {
			evalInto(un.arg, s, sp);
			switch (un.op) {
				case "!" -> {
					byte t = toTriBool(s, sp);
					if (t == TRI_TRUE) {
						s.setBool(sp, false);
					} else if (t == TRI_FALSE) {
						s.setBool(sp, true);
					} else {
						s.setNull(sp);
					}
				}
				case "-" -> {
					switch (s.kinds[sp]) {
						case KIND_INT -> s.setInt(sp, -s.iVals[sp]);
						case KIND_UINT -> s.setFloat(sp, -uint64ToDouble(s.iVals[sp]));
						case KIND_FLOAT -> s.setFloat(sp, -s.fVals[sp]);
						default -> s.setNull(sp);
					}
				}
				case "+" -> {
					if (s.kinds[sp] != KIND_INT && s.kinds[sp] != KIND_UINT && s.kinds[sp] != KIND_FLOAT) {
						s.setNull(sp);
					}
				}
				case "~" -> {
					if (s.kinds[sp] == KIND_INT || s.kinds[sp] == KIND_UINT) {
						s.iVals[sp] = ~s.iVals[sp];
					} else {
						s.setNull(sp);
					}
				}
				default -> s.setNull(sp);
			}
		} else if (node instanceof BinaryNode bin) {
			String op = bin.op;
			if ("&&".equals(op)) {
				evalInto(bin.lhs, s, sp);
				byte lt = toTriBool(s, sp);
				if (lt == TRI_FALSE) {
					s.setBool(sp, false);
					return;
				}
				evalInto(bin.rhs, s, sp + 1);
				byte rt = toTriBool(s, sp + 1);
				if (rt == TRI_FALSE) {
					s.setBool(sp, false);
				} else if (lt == TRI_TRUE && rt == TRI_TRUE) {
					s.setBool(sp, true);
				} else {
					s.setNull(sp);
				}
				return;
			}
			if ("||".equals(op)) {
				evalInto(bin.lhs, s, sp);
				byte lt = toTriBool(s, sp);
				if (lt == TRI_TRUE) {
					s.setBool(sp, true);
					return;
				}
				evalInto(bin.rhs, s, sp + 1);
				byte rt = toTriBool(s, sp + 1);
				if (rt == TRI_TRUE) {
					s.setBool(sp, true);
				} else if (lt == TRI_FALSE && rt == TRI_FALSE) {
					s.setBool(sp, false);
				} else {
					s.setNull(sp);
				}
				return;
			}
			evalInto(bin.lhs, s, sp);
			evalInto(bin.rhs, s, sp + 1);
			if (s.kinds[sp] == KIND_NULL || s.kinds[sp + 1] == KIND_NULL) {
				s.setNull(sp);
				return;
			}
			switch (op) {
				case "==", "!=" -> {
					int cmp = compareNonNull(s, sp, sp + 1, true);
					if (cmp == Integer.MIN_VALUE) {
						s.setNull(sp);
					} else {
						boolean eq = (cmp == 0);
						s.setBool(sp, "==".equals(op) ? eq : !eq);
					}
				}
				case "<", "<=", ">", ">=" -> {
					int cmp = compareNonNull(s, sp, sp + 1, false);
					if (cmp == Integer.MIN_VALUE) {
						s.setNull(sp);
					} else {
						boolean ok = switch (op) {
							case "<" -> cmp < 0;
							case "<=" -> cmp <= 0;
							case ">" -> cmp > 0;
							default -> cmp >= 0;
						};
						s.setBool(sp, ok);
					}
				}
				case "+", "-", "*", "/", "%" -> evalArithmetic(op, s, sp, sp + 1);
				default -> s.setNull(sp);
			}
		} else if (node instanceof TernaryNode tern) {
			evalInto(tern.cond, s, sp);
			byte ct = toTriBool(s, sp);
			if (ct == TRI_TRUE) {
				evalInto(tern.ifTrue, s, sp);
			} else {
				evalInto(tern.ifFalse, s, sp);
			}
		} else if (node instanceof FuncNode fn) {
			switch (fn.name) {
				case "is_null", "null?" -> {
					if (fn.args.isEmpty()) {
						s.setBool(sp, true);
					} else {
						evalInto(fn.args.get(0), s, sp);
						s.setBool(sp, s.kinds[sp] == KIND_NULL);
					}
				}
				case "is_not_null", "not_null?", "not-null?" -> {
					if (fn.args.isEmpty()) {
						s.setBool(sp, false);
					} else {
						evalInto(fn.args.get(0), s, sp);
						s.setBool(sp, s.kinds[sp] != KIND_NULL);
					}
				}
				case "starts_with", "startsWith", "starts-with" -> {
					if (fn.args.size() < 2) {
						s.setNull(sp);
					} else {
						evalInto(fn.args.get(0), s, sp);
						evalInto(fn.args.get(1), s, sp + 1);
						if (s.kinds[sp] != KIND_STR || s.kinds[sp + 1] != KIND_STR) {
							s.setNull(sp);
						} else {
							s.setBool(sp, ((String) s.oVals[sp]).startsWith((String) s.oVals[sp + 1]));
						}
					}
				}
				case "ends_with", "endsWith" -> {
					if (fn.args.size() < 2) {
						s.setNull(sp);
					} else {
						evalInto(fn.args.get(0), s, sp);
						evalInto(fn.args.get(1), s, sp + 1);
						if (s.kinds[sp] != KIND_STR || s.kinds[sp + 1] != KIND_STR) {
							s.setNull(sp);
						} else {
							s.setBool(sp, ((String) s.oVals[sp]).endsWith((String) s.oVals[sp + 1]));
						}
					}
				}
				case "contains" -> {
					if (fn.args.size() < 2) {
						s.setNull(sp);
					} else {
						evalInto(fn.args.get(0), s, sp);
						evalInto(fn.args.get(1), s, sp + 1);
						if (s.kinds[sp] != KIND_STR || s.kinds[sp + 1] != KIND_STR) {
							s.setNull(sp);
						} else {
							s.setBool(sp, ((String) s.oVals[sp]).contains((String) s.oVals[sp + 1]));
						}
					}
				}
				case "coalesce" -> {
					s.setNull(sp);
					for (ExprNode arg : fn.args) {
						evalInto(arg, s, sp);
						if (s.kinds[sp] != KIND_NULL) {
							break;
						}
					}
				}
				case "abs" -> {
					if (fn.args.isEmpty()) {
						s.setNull(sp);
					} else {
						evalInto(fn.args.get(0), s, sp);
						if (s.kinds[sp] == KIND_INT) {
							s.setInt(sp, Math.abs(s.iVals[sp]));
						} else if (s.kinds[sp] == KIND_FLOAT) {
							s.setFloat(sp, Math.abs(s.fVals[sp]));
						} else if (s.kinds[sp] != KIND_UINT) {
							s.setNull(sp);
						}
					}
				}
				default -> s.setNull(sp);
			}
		} else {
			s.setNull(sp);
		}
	}

	private static int compareNonNull(EvalScratch s, int a, int b, boolean equalityMode) {
		byte ka = s.kinds[a];
		byte kb = s.kinds[b];
		if (ka == KIND_STR || kb == KIND_STR) {
			if (ka == KIND_STR && kb == KIND_STR) {
				return ((String) s.oVals[a]).compareTo((String) s.oVals[b]);
			}
			return Integer.MIN_VALUE;
		}
		if (ka == KIND_BOOL && kb == KIND_BOOL) {
			return Long.compare(s.iVals[a], s.iVals[b]);
		}
		if ((ka == KIND_BOOL || kb == KIND_BOOL) && !equalityMode) {
			return Integer.MIN_VALUE;
		}
		if (ka == KIND_FLOAT || kb == KIND_FLOAT) {
			double da = slotAsDouble(s, a);
			double db = slotAsDouble(s, b);
			if (Double.isNaN(da) || Double.isNaN(db)) {
				return Integer.MIN_VALUE;
			}
			if (da == db) {
				return 0;
			}
			return da < db ? -1 : 1;
		}
		long ia = s.iVals[a];
		long ib = s.iVals[b];
		if (ka == KIND_UINT && kb == KIND_UINT) {
			return Long.compareUnsigned(ia, ib);
		}
		if (ka == KIND_UINT) {
			if (ib < 0L) {
				return 1;
			}
			return Long.compareUnsigned(ia, ib);
		}
		if (kb == KIND_UINT) {
			if (ia < 0L) {
				return -1;
			}
			return Long.compareUnsigned(ia, ib);
		}
		return Long.compare(ia, ib);
	}

	private static void evalArithmetic(String op, EvalScratch s, int a, int b) {
		byte ka = s.kinds[a];
		byte kb = s.kinds[b];
		if (ka == KIND_STR || kb == KIND_STR || ka == KIND_BOOL || kb == KIND_BOOL) {
			if ("+".equals(op) && ka == KIND_STR && kb == KIND_STR) {
				s.setStr(a, ((String) s.oVals[a]) + ((String) s.oVals[b]));
				return;
			}
			s.setNull(a);
			return;
		}
		if (ka == KIND_INT && kb == KIND_INT && !"/".equals(op)) {
			long la = s.iVals[a];
			long lb = s.iVals[b];
			switch (op) {
				case "+" -> s.setInt(a, la + lb);
				case "-" -> s.setInt(a, la - lb);
				case "*" -> s.setInt(a, la * lb);
				case "%" -> {
					if (lb == 0L) {
						s.setNull(a);
					} else {
						s.setInt(a, la % lb);
					}
				}
				default -> s.setNull(a);
			}
			return;
		}
		double da = slotAsDouble(s, a);
		double db = slotAsDouble(s, b);
		switch (op) {
			case "+" -> s.setFloat(a, da + db);
			case "-" -> s.setFloat(a, da - db);
			case "*" -> s.setFloat(a, da * db);
			case "/" -> {
				if (db == 0.0) {
					s.setNull(a);
				} else {
					s.setFloat(a, da / db);
				}
			}
			case "%" -> {
				if (db == 0.0) {
					s.setNull(a);
				} else {
					s.setFloat(a, da % db);
				}
			}
			default -> s.setNull(a);
		}
	}

	private static double slotAsDouble(EvalScratch s, int slot) {
		return switch (s.kinds[slot]) {
			case KIND_FLOAT -> s.fVals[slot];
			case KIND_INT, KIND_BOOL -> (double) s.iVals[slot];
			case KIND_UINT -> uint64ToDouble(s.iVals[slot]);
			default -> Double.NaN;
		};
	}

	private static double uint64ToDouble(long u) {
		if (u >= 0L) {
			return (double) u;
		}
		return ((double) (u >>> 1)) * 2.0 + (double) (u & 1L);
	}

	private static void bindJavaValue(EvalScratch s, int sp, Object v) {
		if (v == null) {
			s.setNull(sp);
		} else if (v instanceof Boolean b) {
			s.setBool(sp, b);
		} else if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long) {
			s.setInt(sp, ((Number) v).longValue());
		} else if (v instanceof Float || v instanceof Double) {
			s.setFloat(sp, ((Number) v).doubleValue());
		} else if (v instanceof String str) {
			s.setStr(sp, str);
		} else {
			s.setStr(sp, v.toString());
		}
	}

	private static void readAttrInto(String qualifier, String attrName, EvalScratch s, int sp) {
		if ("id".equals(attrName) || "_id".equals(attrName)) {
			if (!s.edgeMode) {
				s.setInt(sp, s.nodeId);
			} else if ("src".equals(qualifier)) {
				s.setInt(sp, s.srcNodeId);
			} else if ("tgt".equals(qualifier) || "node".equals(qualifier)) {
				s.setInt(sp, s.tgtNodeId);
			} else {
				s.setInt(sp, s.edgeIdx);
			}
			return;
		}
		if (!s.edgeMode) {
			readDomainAttr(s.domain, s.nodeId, attrName, s, sp);
			return;
		}
		if ("src".equals(qualifier)) {
			readDomainAttr(s.srcDomain, s.srcNodeId, attrName, s, sp);
		} else if ("tgt".equals(qualifier) || "node".equals(qualifier)) {
			readDomainAttr(s.tgtDomain, s.tgtNodeId, attrName, s, sp);
		} else if ("edge".equals(qualifier)) {
			readEdgeAttr(s.relation, s.edgeIdx, attrName, s, sp);
		} else {
			if (s.relation != null && s.relation.findAttributeIndex(attrName) >= 0) {
				readEdgeAttr(s.relation, s.edgeIdx, attrName, s, sp);
			} else if (s.tgtDomain != null && s.tgtDomain.getAttributeIndex(attrName) >= 0) {
				readDomainAttr(s.tgtDomain, s.tgtNodeId, attrName, s, sp);
			} else if (s.srcDomain != null && s.srcDomain.getAttributeIndex(attrName) >= 0) {
				readDomainAttr(s.srcDomain, s.srcNodeId, attrName, s, sp);
			} else {
				s.setNull(sp);
			}
		}
	}

	private static void readDomainAttr(LoadedDomain dom, int row, String attrName, EvalScratch s, int sp) {
		if (dom == null || row < 0 || (dom.nodeCount() > 0 && row >= dom.nodeCount())) {
			s.setNull(sp);
			return;
		}
		int idx = dom.getAttributeIndex(attrName);
		if (idx < 0) {
			s.setNull(sp);
			return;
		}
		LoadedAttribute attr = dom.getAttribute(idx);
		MemorySegment dataSeg = dom.getAttributeSegment(idx);
		MemorySegment valSeg = dom.getValiditySegment(idx);
		MemorySegment offsSeg = dom.getOffsetsSegment(idx);
		decodeColumnScalar(attr, dataSeg, valSeg, offsSeg, dom.nodeCount(), row, s, sp);
	}

	private static void readEdgeAttr(RelationSnapshot rel, int edgeRow, String attrName, EvalScratch s, int sp) {
		if (rel == null || edgeRow < 0) {
			s.setNull(sp);
			return;
		}
		int idx = rel.findAttributeIndex(attrName);
		if (idx < 0) {
			s.setNull(sp);
			return;
		}
		List<LoadedAttribute> loaded = rel.getLoadedAttributes();
		LoadedAttribute attr = (idx < loaded.size()) ? loaded.get(idx) : null;
		MemorySegment dataSeg = (idx < rel.getAttributeSegments().size())
				? rel.getAttributeSegments().get(idx)
				: MemorySegment.NULL;
		MemorySegment valSeg = (idx < rel.getValiditySegments().size())
				? rel.getValiditySegments().get(idx)
				: MemorySegment.NULL;
		MemorySegment offsSeg = (idx < rel.getOffsetsSegments().size())
				? rel.getOffsetsSegments().get(idx)
				: MemorySegment.NULL;
		if (attr == null) {
			byte tc = rel.getAttributeTypeCode(idx);
			attr = new LoadedAttribute(rel.getAttributeName(idx), tc, 1, 0L, 0L, 0L,
					dataSeg != null ? dataSeg.byteSize() : 0L, 0L, offsSeg != null ? offsSeg.byteSize() : 0L);
		}
		long rowCount = rel.getEdgeCount();
		if (rowCount > 0 && edgeRow >= rowCount) {
			s.setNull(sp);
			return;
		}
		decodeColumnScalar(attr, dataSeg, valSeg, offsSeg, rowCount, edgeRow, s, sp);
	}

	public static void decodeColumnScalar(LoadedAttribute attr, MemorySegment dataSeg, MemorySegment valSeg,
			MemorySegment offsSeg, long totalRows, int row, EvalScratch s, int sp) {
		if (attr == null || row < 0) {
			s.setNull(sp);
			return;
		}
		if (attr.isNullable()) {
			if (valSeg == null || valSeg.equals(MemorySegment.NULL) || (row >>> 3) >= valSeg.byteSize()) {
				s.setNull(sp);
				return;
			}
			byte vb = valSeg.get(ValueLayout.JAVA_BYTE, row >>> 3);
			if (((vb >>> (row & 7)) & 1) == 0) {
				s.setNull(sp);
				return;
			}
		}
		if (dataSeg == null || dataSeg.equals(MemorySegment.NULL)) {
			s.setNull(sp);
			return;
		}

		int baseCode = attr.typeCode() & 0x7F;
		boolean hasOffsets = offsSeg != null && !offsSeg.equals(MemorySegment.NULL)
				&& offsSeg.byteSize() >= (row + 2L) * 4L;
		long dataSize = dataSeg.byteSize();
		long elemBytes = (totalRows > 0 && !hasOffsets && attr.dimension() <= 1) ? (dataSize / totalRows) : -1L;

		switch (baseCode) {
			case 0x01 -> { // INT8
				if (row + 1L <= dataSize) {
					s.setInt(sp, dataSeg.get(ValueLayout.JAVA_BYTE, row));
				} else {
					s.setNull(sp);
				}
			}
			case 0x02 -> { // INT16
				if ((row + 1L) * 2L <= dataSize) {
					s.setInt(sp, dataSeg.get(ValueLayout.JAVA_SHORT_UNALIGNED, row * 2L));
				} else {
					s.setNull(sp);
				}
			}
			case 0x03 -> { // INT32
				if ((row + 1L) * 4L <= dataSize) {
					s.setInt(sp, dataSeg.get(ValueLayout.JAVA_INT_UNALIGNED, row * 4L));
				} else {
					s.setNull(sp);
				}
			}
			case 0x04 -> { // INT64
				if ((row + 1L) * 8L <= dataSize) {
					s.setInt(sp, dataSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, row * 8L));
				} else {
					s.setNull(sp);
				}
			}
			case 0x11 -> { // UINT8
				if (row + 1L <= dataSize) {
					s.setInt(sp, Byte.toUnsignedLong(dataSeg.get(ValueLayout.JAVA_BYTE, row)));
				} else {
					s.setNull(sp);
				}
			}
			case 0x12 -> { // UINT16
				if ((row + 1L) * 2L <= dataSize) {
					s.setInt(sp, Short.toUnsignedLong(dataSeg.get(ValueLayout.JAVA_SHORT_UNALIGNED, row * 2L)));
				} else {
					s.setNull(sp);
				}
			}
			case 0x13 -> { // UINT32
				if ((row + 1L) * 4L <= dataSize) {
					s.setInt(sp, Integer.toUnsignedLong(dataSeg.get(ValueLayout.JAVA_INT_UNALIGNED, row * 4L)));
				} else {
					s.setNull(sp);
				}
			}
			case 0x14 -> { // UINT64
				if ((row + 1L) * 8L <= dataSize) {
					s.setUint(sp, dataSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, row * 8L));
				} else {
					s.setNull(sp);
				}
			}
			case 0x05 -> { // Spec v0.10.0 FLOAT16 (or legacy v0.9 FLOAT32 when elemBytes == 4)
				if (elemBytes == 4L && (row + 1L) * 4L <= dataSize) {
					s.setFloat(sp, dataSeg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, row * 4L));
				} else if ((row + 1L) * 2L <= dataSize) {
					short h = dataSeg.get(ValueLayout.JAVA_SHORT_UNALIGNED, row * 2L);
					s.setFloat(sp, Float.float16ToFloat(h));
				} else {
					s.setNull(sp);
				}
			}
			case 0x06 -> { // Spec v0.10.0 FLOAT32 (or legacy v0.9 FLOAT64 when elemBytes == 8)
				if (elemBytes == 8L && (row + 1L) * 8L <= dataSize) {
					s.setFloat(sp, dataSeg.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, row * 8L));
				} else if ((row + 1L) * 4L <= dataSize) {
					s.setFloat(sp, dataSeg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, row * 4L));
				} else {
					s.setNull(sp);
				}
			}
			case 0x07 -> { // Spec v0.10.0 FLOAT64 (or legacy BOOL when elemBytes == 1)
				if (elemBytes == 1L && row + 1L <= dataSize) {
					s.setBool(sp, dataSeg.get(ValueLayout.JAVA_BYTE, row) != 0);
				} else if ((row + 1L) * 8L <= dataSize) {
					s.setFloat(sp, dataSeg.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, row * 8L));
				} else {
					s.setNull(sp);
				}
			}
			case 0x08 -> { // BOOL (1B) or Spec v0.10.0 TIMESTAMP_MS (8B)
				if (elemBytes == 8L && (row + 1L) * 8L <= dataSize) {
					s.setInt(sp, dataSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, row * 8L));
				} else if (row + 1L <= dataSize) {
					s.setBool(sp, dataSeg.get(ValueLayout.JAVA_BYTE, row) != 0);
				} else {
					s.setNull(sp);
				}
			}
			case 0x09 -> { // VAR_STRING (with offsets) or Spec v0.10.0 TIMESTAMP_NS (8B)
				if (hasOffsets) {
					readVarString(dataSeg, offsSeg, row, s, sp);
				} else if ((row + 1L) * 8L <= dataSize) {
					s.setInt(sp, dataSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, row * 8L));
				} else {
					s.setNull(sp);
				}
			}
			case 0x0A -> { // UUID128 / FIXED_BYTES[16]
				if ((attr.dimension() == 16 || elemBytes == 16L) && (row + 1L) * 16L <= dataSize) {
					long msb = dataSeg.get(BE_LONG, row * 16L);
					long lsb = dataSeg.get(BE_LONG, row * 16L + 8L);
					s.setStr(sp, new UUID(msb, lsb).toString());
				} else {
					s.setNull(sp);
				}
			}
			case 0x0B -> { // Spec v0.10.0 VAR_STRING (with offsets) or DATE32 (4B)
				if (hasOffsets) {
					readVarString(dataSeg, offsSeg, row, s, sp);
				} else if ((row + 1L) * 4L <= dataSize) {
					s.setInt(sp, dataSeg.get(ValueLayout.JAVA_INT_UNALIGNED, row * 4L));
				} else {
					s.setNull(sp);
				}
			}
			case 0x0C -> { // VAR_BYTES (with offsets) or TIMESTAMP_US (8B) or UUID128 (16B)
				if (hasOffsets) {
					readVarString(dataSeg, offsSeg, row, s, sp);
				} else if (elemBytes == 8L && (row + 1L) * 8L <= dataSize) {
					s.setInt(sp, dataSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, row * 8L));
				} else if ((row + 1L) * 16L <= dataSize) {
					long msb = dataSeg.get(BE_LONG, row * 16L);
					long lsb = dataSeg.get(BE_LONG, row * 16L + 8L);
					s.setStr(sp, new UUID(msb, lsb).toString());
				} else {
					s.setNull(sp);
				}
			}
			case 0x0D -> { // INTERVAL_SEC32 (8B: start_i32, end_i32)
				if ((row + 1L) * 8L <= dataSize) {
					s.setInt(sp, dataSeg.get(ValueLayout.JAVA_INT_UNALIGNED, row * 8L));
				} else {
					s.setNull(sp);
				}
			}
			case 0x0E -> { // FLOAT16 (2B) or INTERVAL_MS64 (16B)
				if (elemBytes == 16L && (row + 1L) * 16L <= dataSize) {
					s.setInt(sp, dataSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, row * 16L));
				} else if ((row + 1L) * 2L <= dataSize) {
					short h = dataSeg.get(ValueLayout.JAVA_SHORT_UNALIGNED, row * 2L);
					s.setFloat(sp, Float.float16ToFloat(h));
				} else {
					s.setNull(sp);
				}
			}
			default -> s.setNull(sp);
		}
	}

	private static void readVarString(MemorySegment dataSeg, MemorySegment offsSeg, int row, EvalScratch s, int sp) {
		long start = Integer.toUnsignedLong(offsSeg.get(ValueLayout.JAVA_INT_UNALIGNED, row * 4L));
		long end = Integer.toUnsignedLong(offsSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (row + 1L) * 4L));
		if (end < start || end > dataSeg.byteSize()) {
			s.setNull(sp);
			return;
		}
		int len = (int) (end - start);
		if (len == 0) {
			s.setStr(sp, "");
			return;
		}
		byte[] bytes = dataSeg.asSlice(start, len).toArray(ValueLayout.JAVA_BYTE);
		s.setStr(sp, new String(bytes, StandardCharsets.UTF_8));
	}

	// =========================================================================
	// AST & Recursive-Descent CEL Parser
	// =========================================================================

	private sealed interface ExprNode permits IntLit, UintLit, FloatLit, BoolLit, StrLit, NullLit, ParamRef, AttrRef,
			UnaryNode, BinaryNode, TernaryNode, FuncNode {
	}

	private record IntLit(long val) implements ExprNode {
	}
	private record UintLit(long val) implements ExprNode {
	}
	private record FloatLit(double val) implements ExprNode {
	}
	private record BoolLit(boolean val) implements ExprNode {
	}
	private record StrLit(String val) implements ExprNode {
	}
	private record NullLit() implements ExprNode {
	}
	private record ParamRef(String name) implements ExprNode {
	}
	private record AttrRef(String qualifier, String name) implements ExprNode {
	}
	private record UnaryNode(String op, ExprNode arg) implements ExprNode {
	}
	private record BinaryNode(String op, ExprNode lhs, ExprNode rhs) implements ExprNode {
	}
	private record TernaryNode(ExprNode cond, ExprNode ifTrue, ExprNode ifFalse) implements ExprNode {
	}
	private record FuncNode(String name, List<ExprNode> args) implements ExprNode {
	}

	private static int computeDepth(ExprNode n) {
		if (n instanceof UnaryNode u) {
			return 1 + computeDepth(u.arg);
		}
		if (n instanceof BinaryNode b) {
			return 2 + Math.max(computeDepth(b.lhs), computeDepth(b.rhs));
		}
		if (n instanceof TernaryNode t) {
			return 2 + Math.max(computeDepth(t.cond), Math.max(computeDepth(t.ifTrue), computeDepth(t.ifFalse)));
		}
		if (n instanceof FuncNode f) {
			int m = 1;
			for (ExprNode a : f.args) {
				m = Math.max(m, 2 + computeDepth(a));
			}
			return m;
		}
		return 1;
	}

	private enum TokKind {
		EOF, INT, UINT, FLOAT, STR, IDENT, PARAM, LP, RP, COMMA, DOT, QMARK, COLON, OR, AND, EQ, NEQ, LT, LTE, GT, GTE, PLUS, MINUS, STAR, SLASH, PCT, BANG, TILDE
	}

	private record Tok(TokKind kind, String text, long iVal, double fVal) {
	}

	private static List<Tok> tokenize(String src) {
		List<Tok> out = new ArrayList<>();
		int i = 0;
		int n = src.length();
		while (i < n) {
			char c = src.charAt(i);
			if (Character.isWhitespace(c)) {
				i++;
				continue;
			}
			if (c == '(') {
				out.add(new Tok(TokKind.LP, "(", 0L, 0.0));
				i++;
			} else if (c == ')') {
				out.add(new Tok(TokKind.RP, ")", 0L, 0.0));
				i++;
			} else if (c == ',') {
				out.add(new Tok(TokKind.COMMA, ",", 0L, 0.0));
				i++;
			} else if (c == '.') {
				out.add(new Tok(TokKind.DOT, ".", 0L, 0.0));
				i++;
			} else if (c == '?') {
				out.add(new Tok(TokKind.QMARK, "?", 0L, 0.0));
				i++;
			} else if (c == ':') {
				out.add(new Tok(TokKind.COLON, ":", 0L, 0.0));
				i++;
			} else if (c == '+') {
				out.add(new Tok(TokKind.PLUS, "+", 0L, 0.0));
				i++;
			} else if (c == '-') {
				out.add(new Tok(TokKind.MINUS, "-", 0L, 0.0));
				i++;
			} else if (c == '*') {
				out.add(new Tok(TokKind.STAR, "*", 0L, 0.0));
				i++;
			} else if (c == '/') {
				out.add(new Tok(TokKind.SLASH, "/", 0L, 0.0));
				i++;
			} else if (c == '%') {
				out.add(new Tok(TokKind.PCT, "%", 0L, 0.0));
				i++;
			} else if (c == '~') {
				out.add(new Tok(TokKind.TILDE, "~", 0L, 0.0));
				i++;
			} else if (c == '&' && i + 1 < n && src.charAt(i + 1) == '&') {
				out.add(new Tok(TokKind.AND, "&&", 0L, 0.0));
				i += 2;
			} else if (c == '|' && i + 1 < n && src.charAt(i + 1) == '|') {
				out.add(new Tok(TokKind.OR, "||", 0L, 0.0));
				i += 2;
			} else if (c == '=' && i + 1 < n && src.charAt(i + 1) == '=') {
				out.add(new Tok(TokKind.EQ, "==", 0L, 0.0));
				i += 2;
			} else if (c == '!' && i + 1 < n && src.charAt(i + 1) == '=') {
				out.add(new Tok(TokKind.NEQ, "!=", 0L, 0.0));
				i += 2;
			} else if (c == '!') {
				out.add(new Tok(TokKind.BANG, "!", 0L, 0.0));
				i++;
			} else if (c == '<' && i + 1 < n && src.charAt(i + 1) == '=') {
				out.add(new Tok(TokKind.LTE, "<=", 0L, 0.0));
				i += 2;
			} else if (c == '<') {
				out.add(new Tok(TokKind.LT, "<", 0L, 0.0));
				i++;
			} else if (c == '>' && i + 1 < n && src.charAt(i + 1) == '=') {
				out.add(new Tok(TokKind.GTE, ">=", 0L, 0.0));
				i += 2;
			} else if (c == '>') {
				out.add(new Tok(TokKind.GT, ">", 0L, 0.0));
				i++;
			} else if (c == '"' || c == '\'') {
				char quote = c;
				i++;
				StringBuilder sb = new StringBuilder();
				while (i < n && src.charAt(i) != quote) {
					char ch = src.charAt(i++);
					if (ch == '\\' && i < n) {
						char esc = src.charAt(i++);
						switch (esc) {
							case 'n' -> sb.append('\n');
							case 'r' -> sb.append('\r');
							case 't' -> sb.append('\t');
							case '\\' -> sb.append('\\');
							case '"' -> sb.append('"');
							case '\'' -> sb.append('\'');
							default -> sb.append(esc);
						}
					} else {
						sb.append(ch);
					}
				}
				if (i < n && src.charAt(i) == quote) {
					i++;
				}
				out.add(new Tok(TokKind.STR, sb.toString(), 0L, 0.0));
			} else if (Character.isDigit(c)) {
				int start = i;
				boolean isFloat = false;
				if (c == '0' && i + 1 < n && (src.charAt(i + 1) == 'x' || src.charAt(i + 1) == 'X')) {
					i += 2;
					while (i < n && Character.digit(src.charAt(i), 16) >= 0) {
						i++;
					}
					long hv = Long.parseUnsignedLong(src.substring(start + 2, i), 16);
					out.add(new Tok(TokKind.INT, src.substring(start, i), hv, 0.0));
					continue;
				}
				while (i < n && Character.isDigit(src.charAt(i))) {
					i++;
				}
				if (i < n && src.charAt(i) == '.' && (i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
					isFloat = true;
					i++;
					while (i < n && Character.isDigit(src.charAt(i))) {
						i++;
					}
				}
				if (i < n && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
					isFloat = true;
					i++;
					if (i < n && (src.charAt(i) == '+' || src.charAt(i) == '-')) {
						i++;
					}
					while (i < n && Character.isDigit(src.charAt(i))) {
						i++;
					}
				}
				String numText = src.substring(start, i);
				boolean isUnsigned = false;
				if (i < n && (src.charAt(i) == 'u' || src.charAt(i) == 'U')) {
					isUnsigned = true;
					i++;
				}
				if (isFloat) {
					out.add(new Tok(TokKind.FLOAT, numText, 0L, Double.parseDouble(numText)));
				} else {
					long uv = Long.parseUnsignedLong(numText);
					if (isUnsigned || uv < 0L) {
						out.add(new Tok(TokKind.UINT, numText, uv, 0.0));
					} else {
						out.add(new Tok(TokKind.INT, numText, uv, 0.0));
					}
				}
			} else if (c == '$' || c == '@') {
				int start = i++;
				while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) {
					i++;
				}
				out.add(new Tok(TokKind.PARAM, src.substring(start, i), 0L, 0.0));
			} else if (Character.isLetter(c) || c == '_') {
				int start = i++;
				while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) {
					i++;
				}
				out.add(new Tok(TokKind.IDENT, src.substring(start, i), 0L, 0.0));
			} else {
				throw new IllegalArgumentException("Unexpected character '" + c + "' in CEL expression: " + src);
			}
		}
		out.add(new Tok(TokKind.EOF, "", 0L, 0.0));
		return out;
	}

	private static ExprNode parseExpression(String src) {
		Parser p = new Parser(tokenize(src));
		ExprNode root = p.parseTernary();
		if (p.peek().kind != TokKind.EOF) {
			throw new IllegalArgumentException("Unexpected trailing token '" + p.peek().text + "' in CEL: " + src);
		}
		return root;
	}

	private static final class Parser {
		private final List<Tok> toks;
		private int pos = 0;

		Parser(List<Tok> toks) {
			this.toks = toks;
		}

		Tok peek() {
			return toks.get(pos);
		}

		Tok next() {
			return toks.get(pos++);
		}

		boolean match(TokKind k) {
			if (peek().kind == k) {
				pos++;
				return true;
			}
			return false;
		}

		ExprNode parseTernary() {
			ExprNode c = parseOr();
			if (match(TokKind.QMARK)) {
				ExprNode t = parseTernary();
				if (!match(TokKind.COLON)) {
					throw new IllegalArgumentException("Expected ':' in ternary CEL expression");
				}
				ExprNode f = parseTernary();
				return new TernaryNode(c, t, f);
			}
			return c;
		}

		ExprNode parseOr() {
			ExprNode left = parseAnd();
			while (match(TokKind.OR)) {
				left = new BinaryNode("||", left, parseAnd());
			}
			return left;
		}

		ExprNode parseAnd() {
			ExprNode left = parseRel();
			while (match(TokKind.AND)) {
				left = new BinaryNode("&&", left, parseRel());
			}
			return left;
		}

		ExprNode parseRel() {
			ExprNode left = parseAdd();
			while (true) {
				TokKind k = peek().kind;
				if (k == TokKind.EQ || k == TokKind.NEQ || k == TokKind.LT || k == TokKind.LTE || k == TokKind.GT
						|| k == TokKind.GTE) {
					String op = next().text;
					left = new BinaryNode(op, left, parseAdd());
				} else {
					break;
				}
			}
			return left;
		}

		ExprNode parseAdd() {
			ExprNode left = parseMul();
			while (peek().kind == TokKind.PLUS || peek().kind == TokKind.MINUS) {
				String op = next().text;
				left = new BinaryNode(op, left, parseMul());
			}
			return left;
		}

		ExprNode parseMul() {
			ExprNode left = parseUnary();
			while (peek().kind == TokKind.STAR || peek().kind == TokKind.SLASH || peek().kind == TokKind.PCT) {
				String op = next().text;
				left = new BinaryNode(op, left, parseUnary());
			}
			return left;
		}

		ExprNode parseUnary() {
			if (peek().kind == TokKind.BANG || peek().kind == TokKind.MINUS || peek().kind == TokKind.PLUS
					|| peek().kind == TokKind.TILDE) {
				String op = next().text;
				return new UnaryNode(op, parseUnary());
			}
			return parsePostfix();
		}

		ExprNode parsePostfix() {
			ExprNode primary = parsePrimary();
			while (match(TokKind.DOT)) {
				Tok member = next();
				if (member.kind != TokKind.IDENT) {
					throw new IllegalArgumentException("Expected identifier after '.'");
				}
				if (match(TokKind.LP)) {
					List<ExprNode> args = new ArrayList<>();
					args.add(primary);
					if (peek().kind != TokKind.RP) {
						do {
							args.add(parseTernary());
						} while (match(TokKind.COMMA));
					}
					if (!match(TokKind.RP)) {
						throw new IllegalArgumentException("Expected ')' after member call");
					}
					primary = new FuncNode(member.text, args);
				} else if (primary instanceof AttrRef base && base.qualifier.isEmpty()) {
					primary = new AttrRef(base.name, member.text);
				} else {
					primary = new AttrRef("", member.text);
				}
			}
			return primary;
		}

		ExprNode parsePrimary() {
			Tok t = next();
			return switch (t.kind) {
				case INT -> new IntLit(t.iVal);
				case UINT -> new UintLit(t.iVal);
				case FLOAT -> new FloatLit(t.fVal);
				case STR -> new StrLit(t.text);
				case PARAM -> new ParamRef(t.text);
				case LP -> {
					ExprNode inner = parseTernary();
					if (!match(TokKind.RP)) {
						throw new IllegalArgumentException("Expected ')'");
					}
					yield inner;
				}
				case IDENT -> {
					if ("true".equalsIgnoreCase(t.text)) {
						yield new BoolLit(true);
					}
					if ("false".equalsIgnoreCase(t.text)) {
						yield new BoolLit(false);
					}
					if ("null".equalsIgnoreCase(t.text)) {
						yield new NullLit();
					}
					if (match(TokKind.LP)) {
						List<ExprNode> args = new ArrayList<>();
						if (peek().kind != TokKind.RP) {
							do {
								args.add(parseTernary());
							} while (match(TokKind.COMMA));
						}
						if (!match(TokKind.RP)) {
							throw new IllegalArgumentException("Expected ')' after function call " + t.text);
						}
						yield new FuncNode(t.text, args);
					}
					yield new AttrRef("", t.text);
				}
				default -> throw new IllegalArgumentException("Unexpected token '" + t.text + "' in CEL expression");
			};
		}
	}
}
