package org.impulsegraph.compiler.impscm.v11;

import org.impulsegraph.api.Decimal64;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.OutputColumnMeta;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.compiler.emitter.V11ProgramBuilder;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.vm.ImpulseImpbContainer;
import org.impulsegraph.vm.ImpulseIsaTables;
import org.impulsegraph.vm.ImpulseValidator;

import java.util.*;

/**
 * ImpScheme v0.11.0 SSA IR ({@code (query ...)}) Parser, Static Domain/Type
 * Validator ({@code IMPSCM_SPECIFICATION_v0_11.md} §7.1), and {@code .impb}
 * v0.11 Compiler with full {@code DECIMAL(P, S)} static typing, strict coercion
 * matrix, compile-time literal comparison boundary folding, and output schema
 * metadata. Mirrors C++ {@code impulse::compiler::v11::ImpScmV11Compiler}.
 */
public final class ImpScmV11Compiler {

	private static final byte TYPE_CODE_DECIMAL64 = 0x15;
	private static final byte TYPE_CODE_DECIMAL64_NULLABLE = (byte) 0x95;

	private ImpScmV11Compiler() {
	}

	public record CompileResult(ImpulseImpbContainer container, Map<String, Integer> paramNameToId,
			List<String> outputNames, List<OutputColumnMeta> outputSchema) {

		public CompileResult(ImpulseImpbContainer container, Map<String, Integer> paramNameToId,
				List<String> outputNames) {
			this(container, paramNameToId, outputNames,
					outputNames.stream().map(n -> OutputColumnMeta.of(n, "UNKNOWN")).toList());
		}

		public long[] codeWords() {
			return container.codeWords;
		}

		public byte[] impbBytes() {
			return container.rawBytes;
		}
	}

	private enum Kind {
		SYMBOL, KEYWORD, PARAM, STRING, INT, FLOAT, BOOL, NULL, LIST
	}

	private static final class SExpr {
		final Kind kind;
		final String text;
		final long iVal;
		final double fVal;
		final boolean bVal;
		final List<SExpr> elems;

		SExpr(Kind k, String t, long iv, double fv, boolean bv, List<SExpr> el) {
			this.kind = k;
			this.text = t;
			this.iVal = iv;
			this.fVal = fv;
			this.bVal = bv;
			this.elems = el;
		}
	}

	private static final class Parser {
		final String src;
		int pos = 0;

		Parser(String src) {
			this.src = src;
		}

		void skipWs() {
			while (pos < src.length()) {
				char c = src.charAt(pos);
				if (Character.isWhitespace(c)) {
					pos++;
				} else if (c == ';') {
					while (pos < src.length() && src.charAt(pos) != '\n')
						pos++;
				} else {
					break;
				}
			}
		}

		SExpr parseOne() {
			skipWs();
			if (pos >= src.length())
				throw new IllegalArgumentException("PARSE_ERROR: unexpected EOF");
			char c = src.charAt(pos);
			if (c == '(' || c == '[') {
				char close = (c == '(') ? ')' : ']';
				pos++;
				List<SExpr> elems = new ArrayList<>();
				while (true) {
					skipWs();
					if (pos >= src.length())
						throw new IllegalArgumentException("PARSE_ERROR: unterminated list");
					if (src.charAt(pos) == close) {
						pos++;
						break;
					}
					elems.add(parseOne());
				}
				return new SExpr(Kind.LIST, "", 0, 0, false, elems);
			}
			if (c == '"') {
				pos++;
				StringBuilder sb = new StringBuilder();
				while (pos < src.length() && src.charAt(pos) != '"') {
					if (src.charAt(pos) == '\\' && pos + 1 < src.length()) {
						pos++;
						char esc = src.charAt(pos++);
						if (esc == 'n')
							sb.append('\n');
						else if (esc == 't')
							sb.append('\t');
						else if (esc == 'r')
							sb.append('\r');
						else
							sb.append(esc);
					} else {
						sb.append(src.charAt(pos++));
					}
				}
				if (pos >= src.length() || src.charAt(pos) != '"')
					throw new IllegalArgumentException("PARSE_ERROR: unterminated string");
				pos++;
				return new SExpr(Kind.STRING, sb.toString(), 0, 0, false, List.of());
			}
			int start = pos;
			while (pos < src.length()) {
				char ch = src.charAt(pos);
				if (Character.isWhitespace(ch) || ch == '(' || ch == ')' || ch == '[' || ch == ']' || ch == ';')
					break;
				pos++;
			}
			String tok = src.substring(start, pos);
			if ("#t".equals(tok))
				return new SExpr(Kind.BOOL, tok, 0, 0, true, List.of());
			if ("#f".equals(tok))
				return new SExpr(Kind.BOOL, tok, 0, 0, false, List.of());
			if ("null".equals(tok))
				return new SExpr(Kind.NULL, tok, 0, 0, false, List.of());
			if (tok.startsWith(":"))
				return new SExpr(Kind.KEYWORD, tok, 0, 0, false, List.of());
			if (tok.startsWith("$"))
				return new SExpr(Kind.PARAM, tok, 0, 0, false, List.of());
			if (Character.isDigit(tok.charAt(0))
					|| (tok.length() > 1 && tok.charAt(0) == '-' && Character.isDigit(tok.charAt(1)))) {
				if (tok.indexOf('.') >= 0 || tok.indexOf('e') >= 0 || tok.indexOf('E') >= 0) {
					return new SExpr(Kind.FLOAT, tok, 0, Double.parseDouble(tok), false, List.of());
				}
				return new SExpr(Kind.INT, tok, Long.parseLong(tok), 0, false, List.of());
			}
			return new SExpr(Kind.SYMBOL, tok, 0, 0, false, List.of());
		}
	}

	private enum ValBank {
		SCALAR_I64, SCALAR_U64, SCALAR_F64, SCALAR_BOOL, SCALAR_STR, SCALAR_DECIMAL, FRONTIER, VEC_I64, VEC_U64, VEC_F64, VEC_BOOL, VEC_STR, VEC_DECIMAL;

		boolean isScalar() {
			return switch (this) {
				case SCALAR_I64, SCALAR_U64, SCALAR_F64, SCALAR_BOOL, SCALAR_STR, SCALAR_DECIMAL -> true;
				default -> false;
			};
		}

		boolean isVec() {
			return switch (this) {
				case VEC_I64, VEC_U64, VEC_F64, VEC_BOOL, VEC_STR, VEC_DECIMAL -> true;
				default -> false;
			};
		}

		boolean isDecimal() {
			return this == SCALAR_DECIMAL || this == VEC_DECIMAL;
		}

		boolean isInt() {
			return this == SCALAR_I64 || this == SCALAR_U64 || this == VEC_I64 || this == VEC_U64;
		}

		boolean isFloat() {
			return this == SCALAR_F64 || this == VEC_F64;
		}

		boolean isStr() {
			return this == SCALAR_STR || this == VEC_STR;
		}

		boolean isBool() {
			return this == SCALAR_BOOL || this == VEC_BOOL;
		}
	}

	private record TypedVal(ValBank bank, int reg, int domId, int precision, int scale, boolean isConst,
			long constVal) {

		static TypedVal scalar(ValBank bank, int reg) {
			return new TypedVal(bank, reg, 0xFFFF, 0, 0, false, 0L);
		}

		static TypedVal scalarWithDom(ValBank bank, int reg, int domId) {
			return new TypedVal(bank, reg, domId, 0, 0, false, 0L);
		}

		static TypedVal constInt(int reg, long val) {
			return new TypedVal(ValBank.SCALAR_I64, reg, 0xFFFF, 0, 0, true, val);
		}

		static TypedVal decimal(int reg, int precision, int scale) {
			return new TypedVal(ValBank.SCALAR_DECIMAL, reg, 0xFFFF, precision, scale, false, 0L);
		}

		static TypedVal constDecimal(int reg, int precision, int scale, long unscaled) {
			return new TypedVal(ValBank.SCALAR_DECIMAL, reg, 0xFFFF, precision, scale, true, unscaled);
		}

		static TypedVal frontier(int reg, int domId) {
			return new TypedVal(ValBank.FRONTIER, reg, domId, 0, 0, false, 0L);
		}

		static TypedVal vec(ValBank bank, int reg, int domId) {
			return new TypedVal(bank, reg, domId, 0, 0, false, 0L);
		}

		static TypedVal vecDecimal(int reg, int domId, int precision, int scale) {
			return new TypedVal(ValBank.VEC_DECIMAL, reg, domId, precision, scale, false, 0L);
		}

		OutputColumnMeta toOutputMeta(String name) {
			if (bank.isDecimal()) {
				return OutputColumnMeta.decimal(name, precision, scale);
			}
			String ty = switch (bank) {
				case SCALAR_I64, VEC_I64 -> "I64";
				case SCALAR_U64, VEC_U64 -> "U64";
				case SCALAR_F64, VEC_F64 -> "F64";
				case SCALAR_BOOL, VEC_BOOL -> "BOOL";
				case SCALAR_STR, VEC_STR -> "STR";
				case FRONTIER -> "FRONTIER";
				default -> "UNKNOWN";
			};
			return OutputColumnMeta.of(name, ty);
		}
	}

	private record RelInfo(int relId, int srcDom, int tgtDom, String name) {
	}

	private record AttrInfo(int attrId, byte typeCode, int precision, int scale) {
		boolean isDecimal() {
			return typeCode == TYPE_CODE_DECIMAL64 || typeCode == TYPE_CODE_DECIMAL64_NULLABLE;
		}
	}

	private record TypeSpec(ValBank scalarBank, int precision, int scale) {
	}

	private static final class MorselScope {
		final RelInfo rel;
		final String srcVar;
		final String tgtVar;
		final String edgeVar;
		int predReg = -1;
		int nextMorselV = 12; // V8=src, V9=tgt, V10=edge, V11=pred, V12..V15=temps

		MorselScope(RelInfo rel, String srcVar, String tgtVar, String edgeVar) {
			this.rel = rel;
			this.srcVar = srcVar;
			this.tgtVar = tgtVar;
			this.edgeVar = edgeVar;
		}

		int allocTempV(int pinnedVMask) {
			for (int attempt = 0; attempt < 4; attempt++) {
				int r = nextMorselV++;
				if (nextMorselV > 15) {
					nextMorselV = 12;
				}
				if (r == predReg || (pinnedVMask & (1 << r)) != 0) {
					continue;
				}
				return r;
			}
			throw new IllegalStateException("REGISTER_EXHAUSTION: morsel V12..V15");
		}
	}

	private static final class Ctx {
		final ImpulseGraphSnapshot snap;
		final V11ProgramBuilder b = new V11ProgramBuilder();
		int nextR = 0, nextF = 0, nextV = 0, nextParamId = 0, outputSlot = 0;
		int pinnedRMask = 0;
		int pinnedVMask = 0;
		Map<String, TypedVal> env = new HashMap<>();
		final Map<String, Integer> paramMap = new LinkedHashMap<>();
		final List<String> outputNames = new ArrayList<>();
		final List<OutputColumnMeta> outputSchema = new ArrayList<>();
		MorselScope morsel = null;
		String nodeFilterVar = null;
		int nodeFilterDom = -1;

		Ctx(ImpulseGraphSnapshot snap) {
			this.snap = snap;
		}

		int liveRMask() {
			int mask = pinnedRMask;
			for (TypedVal v : env.values()) {
				if (v.bank.isScalar() && v.reg >= 0 && v.reg < 16) {
					mask |= (1 << v.reg);
				}
			}
			return mask;
		}

		int liveVMask() {
			int mask = pinnedVMask;
			for (TypedVal v : env.values()) {
				if (v.bank.isVec() && v.reg >= 0 && v.reg < 8) {
					mask |= (1 << v.reg);
				}
			}
			return mask;
		}

		int allocR() {
			if (nextR < 16) {
				int r = nextR++;
				return r;
			}
			int live = liveRMask();
			for (int r = 0; r < 16; r++) {
				if ((live & (1 << r)) == 0) {
					return r;
				}
			}
			throw new IllegalStateException("REGISTER_EXHAUSTION: R0..R15");
		}

		int allocF() {
			if (nextF >= 16)
				throw new IllegalStateException("REGISTER_EXHAUSTION: F0..F15");
			return nextF++;
		}

		int allocV() {
			if (morsel != null) {
				return morsel.allocTempV(pinnedVMask);
			}
			if (nextV < 8)
				return nextV++;
			int live = liveVMask();
			for (int v = 0; v < 8; v++) {
				if ((live & (1 << v)) == 0) {
					return v;
				}
			}
			throw new IllegalStateException("REGISTER_EXHAUSTION: V0..V7");
		}

		int pinR(TypedVal v) {
			int prev = pinnedRMask;
			if (v != null && v.bank.isScalar() && v.reg >= 0 && v.reg < 16) {
				pinnedRMask |= (1 << v.reg);
			}
			return prev;
		}

		int pinV(TypedVal v) {
			int prev = pinnedVMask;
			if (v != null && v.bank.isVec() && v.reg >= 0 && v.reg < 16) {
				pinnedVMask |= (1 << v.reg);
			}
			return prev;
		}

		int allocPinnedR() {
			int r = allocR();
			pinnedRMask |= (1 << r);
			return r;
		}

		int allocPinnedV() {
			int v = allocV();
			pinnedVMask |= (1 << v);
			return v;
		}

		boolean isVecInEnv(int reg) {
			for (TypedVal v : env.values()) {
				if (v.bank.isVec() && v.reg == reg) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Allocates a destination vector register, allowing safe in-place reuse of a
		 * non-env temporary operand register when all V0..V7 slots are occupied (VM
		 * vector ops read inputs before writing {@code ws.vVectors[dst]}).
		 */
		int allocDstV(TypedVal t1, TypedVal t2) {
			if (morsel != null) {
				return morsel.allocTempV(pinnedVMask);
			}
			if (nextV < 8) {
				return nextV++;
			}
			int live = liveVMask();
			for (int v = 0; v < 8; v++) {
				if ((live & (1 << v)) == 0) {
					return v;
				}
			}
			if (t1 != null && t1.bank.isVec() && t1.reg >= 0 && t1.reg < 8 && !isVecInEnv(t1.reg)) {
				return t1.reg;
			}
			if (t2 != null && t2.bank.isVec() && t2.reg >= 0 && t2.reg < 8 && !isVecInEnv(t2.reg)) {
				return t2.reg;
			}
			throw new IllegalStateException("REGISTER_EXHAUSTION: V0..V7");
		}

		int resolveDomain(String name) {
			if (snap == null)
				return 0;
			if (snap instanceof GraphSnapshot gs) {
				BinarySnapshotLoader.LoadedDomain ld = gs.getDomain(name);
				if (ld != null)
					return ld.domainId();
			}
			if (snap.getDomainNames() != null) {
				int idx = 0;
				for (String dn : snap.getDomainNames()) {
					if (dn.equals(name))
						return idx;
					idx++;
				}
			}
			throw new IllegalArgumentException("UNKNOWN_SCHEMA_ENTITY: unknown domain '" + name + "'");
		}

		String resolveDomainName(int domId) {
			if (snap instanceof GraphSnapshot gs) {
				for (String dn : gs.getDomainNames()) {
					BinarySnapshotLoader.LoadedDomain ld = gs.getDomain(dn);
					if (ld != null && ld.domainId() == domId) {
						return dn;
					}
				}
			}
			if (snap != null && snap.getDomainNames() != null) {
				int idx = 0;
				for (String dn : snap.getDomainNames()) {
					if (idx == domId)
						return dn;
					idx++;
				}
			}
			return null;
		}

		RelInfo resolveRel(String name) {
			if (snap == null)
				return new RelInfo(0, 0, 0, name);
			RelationSnapshot rs = snap.getRelationSnapshot(name);
			if (rs == null) {
				throw new IllegalArgumentException("UNKNOWN_SCHEMA_ENTITY: unknown relation '" + name + "'");
			}
			int rid = rs.getRelationId() >= 0 ? rs.getRelationId() : 0;
			return new RelInfo(rid, rs.getSrcDomainId(), rs.getTgtDomainId(), name);
		}

		AttrInfo resolveNodeAttr(int domId, String attrName) {
			if (snap instanceof GraphSnapshot gs) {
				String domName = resolveDomainName(domId);
				if (domName != null) {
					BinarySnapshotLoader.LoadedDomain ld = gs.getDomain(domName);
					if (ld != null) {
						int idx = ld.getAttributeIndex(attrName);
						if (idx >= 0) {
							BinarySnapshotLoader.LoadedAttribute la = ld.getAttribute(idx);
							return new AttrInfo(idx, la.typeCode(), la.precision(), la.scale());
						}
					}
				}
			}
			throw new IllegalArgumentException(
					"UNKNOWN_SCHEMA_ENTITY: unknown node attribute '" + attrName + "' on domain " + domId);
		}

		AttrInfo resolveEdgeAttr(String relName, String attrName) {
			if (snap != null) {
				RelationSnapshot rs = snap.getRelationSnapshot(relName);
				if (rs instanceof org.impulsegraph.storage.csr.RelationSnapshot csrRel) {
					List<BinarySnapshotLoader.LoadedAttribute> attrs = csrRel.getLoadedAttributes();
					for (int i = 0; i < attrs.size(); i++) {
						BinarySnapshotLoader.LoadedAttribute la = attrs.get(i);
						if (la.name().equals(attrName)) {
							return new AttrInfo(i, la.typeCode(), la.precision(), la.scale());
						}
					}
				}
			}
			throw new IllegalArgumentException(
					"UNKNOWN_SCHEMA_ENTITY: unknown edge attribute '" + attrName + "' on relation '" + relName + "'");
		}

		ValBank attrTypeToVecBank(AttrInfo ai) {
			if (ai.isDecimal()) {
				return ValBank.VEC_DECIMAL;
			}
			int base = ai.typeCode & 0x7F;
			return switch (base) {
				case 0x05, 0x06 -> ValBank.VEC_F64; // F32, F64
				case 0x09 -> ValBank.VEC_BOOL; // BOOL
				case 0x0A -> ValBank.VEC_STR; // STRING
				case 0x07, 0x08 -> ValBank.VEC_U64; // U32, U64
				default -> ValBank.VEC_I64;
			};
		}

		TypeSpec parseScalarTypeSpec(SExpr ty) {
			if (ty.kind == Kind.LIST && ty.elems.size() == 3 && "decimal".equals(ty.elems.get(0).text)) {
				if (ty.elems.get(1).kind != Kind.INT || ty.elems.get(2).kind != Kind.INT) {
					throw new IllegalArgumentException("PARSE_ERROR: (decimal P S) requires integer P and S");
				}
				long pRaw = ty.elems.get(1).iVal;
				long sRaw = ty.elems.get(2).iVal;
				if (pRaw < 1L || pRaw > 18L || sRaw < 0L || sRaw > pRaw) {
					throw new IllegalArgumentException("PARSE_ERROR: invalid DECIMAL(" + pRaw + ", " + sRaw + ")");
				}
				int p = (int) pRaw;
				int s = (int) sRaw;
				Decimal64.validatePrecisionScale(p, s);
				return new TypeSpec(ValBank.SCALAR_DECIMAL, p, s);
			}
			if (ty.kind == Kind.SYMBOL) {
				return switch (ty.text) {
					case "i64", "i32" -> new TypeSpec(ValBank.SCALAR_I64, 0, 0);
					case "u64", "u32" -> new TypeSpec(ValBank.SCALAR_U64, 0, 0);
					case "f64", "f32" -> new TypeSpec(ValBank.SCALAR_F64, 0, 0);
					case "bool" -> new TypeSpec(ValBank.SCALAR_BOOL, 0, 0);
					case "str" -> new TypeSpec(ValBank.SCALAR_STR, 0, 0);
					default -> throw new IllegalArgumentException("PARSE_ERROR: unknown type '" + ty.text + "'");
				};
			}
			throw new IllegalArgumentException("PARSE_ERROR: unsupported type expression");
		}

		private TypedVal ensureDecimal(TypedVal v, String opName) {
			if (v.bank.isDecimal()) {
				return v;
			}
			if (v.bank.isInt()) {
				// Exact implicit promotion: INT -> DECIMAL(18, 0)
				if (v.bank.isScalar()) {
					return v.isConst ? TypedVal.constDecimal(v.reg, 18, 0, v.constVal) : TypedVal.decimal(v.reg, 18, 0);
				}
				return TypedVal.vecDecimal(v.reg, v.domId, 18, 0);
			}
			throw new IllegalArgumentException("TYPE_MISMATCH: decimal operator '" + opName
					+ "' does not allow operand of type " + v.bank + "; use (cast ...)");
		}

		private TypedVal rescaleDecimalUp(TypedVal v, int targetScale) {
			int delta = targetScale - v.scale;
			if (delta == 0) {
				return v;
			}
			int newP = Math.min(18, v.precision + delta);
			if (v.bank.isScalar()) {
				int savedPin = pinR(v);
				int dst = allocR();
				pinnedRMask = savedPin;
				b.emitScalarDecRescale(dst, v.reg, delta, false);
				return TypedVal.decimal(dst, newP, targetScale);
			}
			int dst = allocV();
			b.emitVecDecRescale(dst, v.reg, delta, false, 0, 0);
			return TypedVal.vecDecimal(dst, v.domId, newP, targetScale);
		}

		private TypedVal evalDecimalBinary(String head, TypedVal rawLhs, TypedVal rawRhs) {
			int savedPinR = pinR(rawLhs);
			int savedPinV = pinV(rawLhs);
			pinR(rawRhs);
			pinV(rawRhs);
			try {
				TypedVal lhs = ensureDecimal(rawLhs, head);
				TypedVal rhs = ensureDecimal(rawRhs, head);

				boolean lVec = lhs.bank.isVec();
				boolean rVec = rhs.bank.isVec();
				if (lVec && rVec && lhs.domId != rhs.domId) {
					throw new IllegalArgumentException("DOMAIN_MISMATCH: vector decimal op domain mismatch");
				}
				int outDom = lVec ? lhs.domId : (rVec ? rhs.domId : 0xFFFF);

				if ("+".equals(head) || "-".equals(head)) {
					int s = Math.max(lhs.scale, rhs.scale);
					int p = Math.min(18, Math.max(lhs.precision - lhs.scale, rhs.precision - rhs.scale) + s + 1);
					TypedVal a = rescaleDecimalUp(lhs, s);
					pinR(a);
					pinV(a);
					TypedVal bVal = rescaleDecimalUp(rhs, s);
					pinR(bVal);
					pinV(bVal);

					if (!lVec && !rVec) {
						int dst = allocR();
						if ("+".equals(head)) {
							b.emitScalarDecAddChk(dst, a.reg, bVal.reg);
						} else {
							b.emitScalarDecSubChk(dst, a.reg, bVal.reg);
						}
						return TypedVal.decimal(dst, p, s);
					}
					if (lVec && rVec) {
						int dst = allocDstV(a, bVal);
						if ("+".equals(head)) {
							b.emitVecDecAddChk(dst, a.reg, bVal.reg, 0, 0);
						} else {
							b.emitVecDecSubChk(dst, a.reg, bVal.reg, 0, 0);
						}
						return TypedVal.vecDecimal(dst, outDom, p, s);
					}
					if (lVec) {
						int dst = allocDstV(a, null);
						if ("+".equals(head)) {
							b.emitVecDecAddChk(dst, a.reg, bVal.reg, ImpulseIsaTables.FLAG_SCALAR_RHS, 0);
						} else {
							b.emitVecDecSubChk(dst, a.reg, bVal.reg, ImpulseIsaTables.FLAG_SCALAR_RHS, 0);
						}
						return TypedVal.vecDecimal(dst, outDom, p, s);
					}
					// scalar +/- vec
					if ("+".equals(head)) {
						int dst = allocDstV(bVal, null);
						b.emitVecDecAddChk(dst, bVal.reg, a.reg, ImpulseIsaTables.FLAG_SCALAR_RHS, 0);
						return TypedVal.vecDecimal(dst, outDom, p, s);
					}
					// scalar - vec => (-vec) + scalar
					int negV = allocPinnedV();
					b.emitVecDecNegChk(negV, bVal.reg, 0, 0);
					int dst = allocDstV(bVal, null);
					b.emitVecDecAddChk(dst, negV, a.reg, ImpulseIsaTables.FLAG_SCALAR_RHS, 0);
					return TypedVal.vecDecimal(dst, outDom, p, s);
				}

				if ("*".equals(head)) {
					int rawS = lhs.scale + rhs.scale;
					int s = Math.min(18, rawS);
					int p = Math.min(18, lhs.precision + rhs.precision);
					int divPow = rawS - s; // 0 when rawS <= 18, else 1..18

					if (!lVec && !rVec) {
						int dst = allocPinnedR();
						if (divPow == 0) {
							b.emitScalarDecMulChk(dst, lhs.reg, rhs.reg);
						} else {
							int rDiv = allocR();
							b.emitLoadConstI64(rDiv, Decimal64.POW10[divPow]);
							b.emitScalarDecMulDivRound(dst, lhs.reg, rhs.reg, rDiv);
						}
						return TypedVal.decimal(dst, p, s);
					}
					// Vector * Vector, Vector * Scalar, or Scalar * Vector (commutative)
					TypedVal vOp = lVec ? lhs : rhs;
					TypedVal other = lVec ? rhs : lhs;
					boolean scalarRhs = !other.bank.isVec();
					int flags = scalarRhs ? ImpulseIsaTables.FLAG_SCALAR_RHS : 0;
					int dst = allocDstV(vOp, scalarRhs ? null : other);
					if (divPow == 0) {
						b.emitVecDecMulChk(dst, vOp.reg, other.reg, flags, 0);
					} else {
						int rDiv = allocR();
						b.emitLoadConstI64(rDiv, Decimal64.POW10[divPow]);
						b.emitVecDecMulDivR(dst, vOp.reg, other.reg, rDiv, flags);
					}
					return TypedVal.vecDecimal(dst, outDom, p, s);
				}

				// "/" or "safe-div"
				boolean isSafe = "safe-div".equals(head);
				int s = Math.min(18, Math.max(6, lhs.scale));
				int p = 18;
				int rawK = s - lhs.scale + rhs.scale; // always >= 0

				if (!lVec && !rVec) {
					int dst = allocPinnedR();
					if (!isSafe) {
						TypedVal num = lhs;
						int k = rawK;
						if (k > 18) {
							num = rescaleDecimalUp(lhs, lhs.scale + (k - 18));
							pinR(num);
							k = 18;
						}
						int rMul = allocR();
						b.emitLoadConstI64(rMul, Decimal64.POW10[k]);
						b.emitScalarDecMulDivRound(dst, num.reg, rMul, rhs.reg);
					} else if (rawK == 0) {
						b.emitScalarDecDivSafe(dst, lhs.reg, rhs.reg);
					} else {
						// Safe-div with 128-bit scaling: check rhs == 0 BEFORE any k > 18 pre-rescale!
						int rZero = allocPinnedR();
						int rIsZero = allocPinnedR();
						b.emitLoadConstI64(rZero, 0L);
						b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_CMP_EQ_I64, 0, rIsZero,
								rhs.reg, rZero, 0, 0);
						int jccPc = b.emit(ImpulseIsaTables.OP_JCC, 6, 0, 0, rIsZero, 0, 0, 0);
						TypedVal num = lhs;
						int k = rawK;
						if (k > 18) {
							num = rescaleDecimalUp(lhs, lhs.scale + (k - 18));
							pinR(num);
							k = 18;
						}
						int rMul = allocR();
						b.emitLoadConstI64(rMul, Decimal64.POW10[k]);
						b.emitScalarDecMulDivRound(dst, num.reg, rMul, rhs.reg);
						int jmpEndPc = b.emit(ImpulseIsaTables.OP_JMP, 0, 0, 0, 0, 0, 0, 0);
						int nullPc = b.pc();
						b.patchImm32(jccPc, nullPc - jccPc);
						b.emit(ImpulseIsaTables.OP_LOAD_CONST, ImpulseIsaTables.SUBOP_LOAD_CONST_NULL, 0, dst, 0, 0, 0,
								0);
						int endPc = b.pc();
						b.patchImm32(jmpEndPc, endPc - jmpEndPc);
					}
					return TypedVal.decimal(dst, p, s);
				}

				TypedVal num = lhs;
				if (!lVec) {
					// scalar / vec: materialize scalar into domain vector via OP_VEC_INIT
					int vNum = allocPinnedV();
					b.emitU16(ImpulseIsaTables.OP_VEC_INIT, ImpulseIsaTables.SUBOP_VEC_INIT_DOM_I64,
							ImpulseIsaTables.FLAG_SCALAR_RHS, vNum, 0, num.reg, 0, outDom, 0);
					num = TypedVal.vecDecimal(vNum, outDom, num.precision, num.scale);
				}

				if (!rVec) {
					// vec / scalar
					int k = rawK;
					if (isSafe && k > 18) {
						// Guard k > 18 pre-rescale against rhs == 0
						int dst = allocPinnedV();
						int rZero = allocPinnedR();
						int rIsZero = allocPinnedR();
						b.emitLoadConstI64(rZero, 0L);
						b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_CMP_EQ_I64, 0, rIsZero,
								rhs.reg, rZero, 0, 0);
						int jccPc = b.emit(ImpulseIsaTables.OP_JCC, 6, 0, 0, rIsZero, 0, 0, 0);
						TypedVal preNum = rescaleDecimalUp(num, num.scale + (k - 18));
						pinV(preNum);
						int rMul = allocR();
						b.emitLoadConstI64(rMul, Decimal64.POW10[18]);
						b.emitVecDecMulDivSafeR(dst, preNum.reg, rMul, rhs.reg, ImpulseIsaTables.FLAG_SCALAR_RHS);
						int jmpEndPc = b.emit(ImpulseIsaTables.OP_JMP, 0, 0, 0, 0, 0, 0, 0);
						int nullPc = b.pc();
						b.patchImm32(jccPc, nullPc - jccPc);
						int rNull = allocR();
						b.emit(ImpulseIsaTables.OP_LOAD_CONST, ImpulseIsaTables.SUBOP_LOAD_CONST_NULL, 0, rNull, 0, 0,
								0, 0);
						b.emitU16(ImpulseIsaTables.OP_VEC_INIT, ImpulseIsaTables.SUBOP_VEC_INIT_DOM_I64,
								ImpulseIsaTables.FLAG_SCALAR_RHS, dst, 0, rNull, 0, outDom, 0);
						int endPc = b.pc();
						b.patchImm32(jmpEndPc, endPc - jmpEndPc);
						return TypedVal.vecDecimal(dst, outDom, p, s);
					}
					if (k > 18) {
						num = rescaleDecimalUp(num, num.scale + (k - 18));
						pinV(num);
						k = 18;
					}
					int dst = allocV();
					int rMul = allocR();
					b.emitLoadConstI64(rMul, Decimal64.POW10[k]);
					if (isSafe) {
						b.emitVecDecMulDivSafeR(dst, num.reg, rMul, rhs.reg, ImpulseIsaTables.FLAG_SCALAR_RHS);
					} else {
						b.emitVecDecMulDivR(dst, num.reg, rMul, rhs.reg, ImpulseIsaTables.FLAG_SCALAR_RHS);
					}
					return TypedVal.vecDecimal(dst, outDom, p, s);
				}

				// vec / vec
				if (isSafe) {
					// Zero-check blend + 128-bit mul_div_v:
					// 1) vNonZero = (rhs != 0)
					// 2) vSafeRhs = blend_kleene_vr(vNonZero, rhs, NULL) -> 0 lanes become NULL
					// 3) if k > 18: mask num with vNonZero before pre-rescale so 0-divisor lanes
					// never trap
					// 4) emit 128-bit OP_VEC_DECIMAL_ALU.mul_div_v(num, 10^k, vSafeRhs)
					int rZero = allocPinnedR();
					int rNull = allocPinnedR();
					b.emitLoadConstI64(rZero, 0L);
					b.emit(ImpulseIsaTables.OP_LOAD_CONST, ImpulseIsaTables.SUBOP_LOAD_CONST_NULL, 0, rNull, 0, 0, 0,
							0);
					int vNonZero = allocPinnedV();
					b.emit(ImpulseIsaTables.OP_VEC_CMP, ImpulseIsaTables.SUBOP_VEC_CMP_NE,
							ImpulseIsaTables.FLAG_SCALAR_RHS, vNonZero, rhs.reg, rZero, 0, 0);
					int vSafeRhs = allocPinnedV();
					b.emit(ImpulseIsaTables.OP_VEC_BLEND, ImpulseIsaTables.SUBOP_VEC_BLEND_KLEENE_VR, 0, vSafeRhs,
							vNonZero, rhs.reg, rNull, 0);
					int k = rawK;
					if (k > 18) {
						int vSafeNum = allocPinnedV();
						b.emit(ImpulseIsaTables.OP_VEC_BLEND, ImpulseIsaTables.SUBOP_VEC_BLEND_KLEENE_VR, 0, vSafeNum,
								vNonZero, num.reg, rNull, 0);
						num = rescaleDecimalUp(TypedVal.vecDecimal(vSafeNum, outDom, num.precision, num.scale),
								num.scale + (k - 18));
						pinV(num);
						k = 18;
					}
					int rMul = allocR();
					b.emitLoadConstI64(rMul, Decimal64.POW10[k]);
					int dst = allocV();
					b.emitVecDecMulDivV(dst, num.reg, rMul, vSafeRhs, ImpulseIsaTables.FLAG_SCALAR_RHS);
					return TypedVal.vecDecimal(dst, outDom, p, s);
				}
				int k = rawK;
				if (k > 18) {
					num = rescaleDecimalUp(num, num.scale + (k - 18));
					pinV(num);
					k = 18;
				}
				int dst = allocV();
				int rMul = allocR();
				b.emitLoadConstI64(rMul, Decimal64.POW10[k]);
				b.emitVecDecMulDivV(dst, num.reg, rMul, rhs.reg, ImpulseIsaTables.FLAG_SCALAR_RHS);
				return TypedVal.vecDecimal(dst, outDom, p, s);
			} finally {
				pinnedRMask = savedPinR;
				pinnedVMask = savedPinV;
			}
		}

		private TypedVal evalCast(TypedVal v, TypeSpec target) {
			int savedPinR = pinR(v);
			int savedPinV = pinV(v);
			try {
				boolean isVec = v.bank.isVec();
				if (target.scalarBank == ValBank.SCALAR_DECIMAL) {
					int tp = target.precision;
					int ts = target.scale;
					if (v.bank.isDecimal()) {
						// DECIMAL(P1, S1) -> DECIMAL(P2, S2)
						TypedVal cur = v;
						if (ts > v.scale) {
							int delta = ts - v.scale;
							if (!isVec) {
								int r1 = allocR();
								b.emitScalarDecRescale(r1, v.reg, delta, false);
								int r2 = allocR();
								b.emitScalarDecBoundChk(r2, r1, tp);
								return TypedVal.decimal(r2, tp, ts);
							}
							int v1 = allocV();
							b.emitVecDecRescale(v1, v.reg, delta, false, 0, 0);
							int v2 = allocV();
							b.emitVecDecBoundChk(v2, v1, tp, 0, 0);
							return TypedVal.vecDecimal(v2, v.domId, tp, ts);
						} else if (ts < v.scale) {
							int delta = v.scale - ts;
							if (!isVec) {
								int r1 = allocR();
								b.emitScalarDecRoundScale(r1, v.reg, delta);
								int r2 = allocR();
								b.emitScalarDecBoundChk(r2, r1, tp);
								return TypedVal.decimal(r2, tp, ts);
							}
							int v1 = allocV();
							b.emitVecDecRoundScale(v1, v.reg, delta, 0, 0);
							int v2 = allocV();
							b.emitVecDecBoundChk(v2, v1, tp, 0, 0);
							return TypedVal.vecDecimal(v2, v.domId, tp, ts);
						} else {
							if (tp < v.precision) {
								if (!isVec) {
									int r2 = allocR();
									b.emitScalarDecBoundChk(r2, v.reg, tp);
									return TypedVal.decimal(r2, tp, ts);
								}
								int v2 = allocV();
								b.emitVecDecBoundChk(v2, v.reg, tp, 0, 0);
								return TypedVal.vecDecimal(v2, v.domId, tp, ts);
							}
							return !isVec
									? TypedVal.decimal(v.reg, tp, ts)
									: TypedVal.vecDecimal(v.reg, v.domId, tp, ts);
						}
					}
					if (v.bank.isInt()) {
						// INT -> DECIMAL(P, S): rescale(x, S, mul) + bound_chk(P)
						if (!isVec) {
							int curR = v.reg;
							if (ts > 0) {
								int r1 = allocR();
								b.emitScalarDecRescale(r1, curR, ts, false);
								curR = r1;
							}
							int r2 = allocR();
							b.emitScalarDecBoundChk(r2, curR, tp);
							return TypedVal.decimal(r2, tp, ts);
						}
						int curV = v.reg;
						if (ts > 0) {
							int v1 = allocV();
							b.emitVecDecRescale(v1, curV, ts, false, 0, 0);
							curV = v1;
						}
						int v2 = allocV();
						b.emitVecDecBoundChk(v2, curV, tp, 0, 0);
						return TypedVal.vecDecimal(v2, v.domId, tp, ts);
					}
					if (v.bank.isFloat()) {
						// FLOAT64 -> DECIMAL(P, S): f64_to_dec(x, S, P)
						if (!isVec) {
							int dst = allocR();
							b.emitScalarDecF64ToDec(dst, v.reg, ts, tp);
							return TypedVal.decimal(dst, tp, ts);
						}
						int dst = allocV();
						b.emitVecDecF64ToDec(dst, v.reg, ts, tp, 0, 0);
						return TypedVal.vecDecimal(dst, v.domId, tp, ts);
					}
					throw new IllegalArgumentException("TYPE_MISMATCH: cannot cast " + v.bank + " to DECIMAL");
				}

				if (target.scalarBank == ValBank.SCALAR_F64) {
					if (v.bank.isDecimal()) {
						// DECIMAL(P, S) -> FLOAT64: dec_to_f64(x, S)
						if (!isVec) {
							int dst = allocR();
							b.emitScalarDecToF64(dst, v.reg, v.scale);
							return TypedVal.scalar(ValBank.SCALAR_F64, dst);
						}
						int dst = allocV();
						b.emitVecDecToF64(dst, v.reg, v.scale, 0, 0);
						return TypedVal.vec(ValBank.VEC_F64, dst, v.domId);
					}
					if (!isVec) {
						int dst = allocR();
						b.emit(ImpulseIsaTables.OP_SCALAR_FP_ALU, ImpulseIsaTables.SUBOP_SCALAR_FP_ALU_I64_TO_F64, 0,
								dst, v.reg, 0, 0, 0);
						return TypedVal.scalar(ValBank.SCALAR_F64, dst);
					}
					int dst = allocV();
					b.emitU16(ImpulseIsaTables.OP_VEC_POINT, ImpulseIsaTables.SUBOP_VEC_POINT_VEC_CAST, 0, dst, v.reg,
							0, 0, 4, 0); // 4 = F64
					return TypedVal.vec(ValBank.VEC_F64, dst, v.domId);
				}

				if (target.scalarBank == ValBank.SCALAR_I64 || target.scalarBank == ValBank.SCALAR_U64) {
					if (v.bank.isDecimal()) {
						// DECIMAL(P, S) -> INT: trunc_scale(x, S)
						if (!isVec) {
							if (v.scale == 0) {
								return TypedVal.scalar(target.scalarBank, v.reg);
							}
							int dst = allocR();
							b.emitScalarDecTruncScale(dst, v.reg, v.scale);
							return TypedVal.scalar(target.scalarBank, dst);
						}
						ValBank outVb = (target.scalarBank == ValBank.SCALAR_U64) ? ValBank.VEC_U64 : ValBank.VEC_I64;
						if (v.scale == 0) {
							return TypedVal.vec(outVb, v.reg, v.domId);
						}
						int dst = allocV();
						b.emitVecDecTruncScale(dst, v.reg, v.scale, 0, 0);
						return TypedVal.vec(outVb, dst, v.domId);
					}
					if (!isVec) {
						int dst = allocR();
						b.emit(ImpulseIsaTables.OP_SCALAR_FP_ALU, ImpulseIsaTables.SUBOP_SCALAR_FP_ALU_F64_TO_I64, 0,
								dst, v.reg, 0, 0, 0);
						return TypedVal.scalar(target.scalarBank, dst);
					}
					int dst = allocV();
					int tyCode = (target.scalarBank == ValBank.SCALAR_U64) ? 1 : 0;
					b.emitU16(ImpulseIsaTables.OP_VEC_POINT, ImpulseIsaTables.SUBOP_VEC_POINT_VEC_CAST, 0, dst, v.reg,
							0, 0, tyCode, 0);
					return TypedVal.vec((tyCode == 1) ? ValBank.VEC_U64 : ValBank.VEC_I64, dst, v.domId);
				}
				throw new IllegalArgumentException("TYPE_MISMATCH: unsupported cast target " + target.scalarBank);
			} finally {
				pinnedRMask = savedPinR;
				pinnedVMask = savedPinV;
			}
		}

		private static DecimalBoundaryFolder.CmpOp parseCmpOp(String op) {
			return switch (op) {
				case "=" -> DecimalBoundaryFolder.CmpOp.EQ;
				case "!=" -> DecimalBoundaryFolder.CmpOp.NE;
				case "<" -> DecimalBoundaryFolder.CmpOp.LT;
				case "<=" -> DecimalBoundaryFolder.CmpOp.LE;
				case ">" -> DecimalBoundaryFolder.CmpOp.GT;
				case ">=" -> DecimalBoundaryFolder.CmpOp.GE;
				default -> null;
			};
		}

		private static int cmpOpToNodeFilterSubop(DecimalBoundaryFolder.CmpOp op) {
			return switch (op) {
				case EQ -> ImpulseIsaTables.SUBOP_NODE_FILTER_EQ;
				case NE -> ImpulseIsaTables.SUBOP_NODE_FILTER_NE;
				case LT -> ImpulseIsaTables.SUBOP_NODE_FILTER_LT;
				case LE -> ImpulseIsaTables.SUBOP_NODE_FILTER_LE;
				case GT -> ImpulseIsaTables.SUBOP_NODE_FILTER_GT;
				case GE -> ImpulseIsaTables.SUBOP_NODE_FILTER_GE;
			};
		}

		private static int cmpOpToIndexScanSubop(DecimalBoundaryFolder.CmpOp op) {
			return switch (op) {
				case EQ -> ImpulseIsaTables.SUBOP_INDEX_SCAN_EQ;
				case LT -> ImpulseIsaTables.SUBOP_INDEX_SCAN_LT;
				case LE -> ImpulseIsaTables.SUBOP_INDEX_SCAN_LE;
				case GT -> ImpulseIsaTables.SUBOP_INDEX_SCAN_GT;
				case GE -> ImpulseIsaTables.SUBOP_INDEX_SCAN_GE;
				case NE -> throw new IllegalArgumentException("OP_INDEX_SCAN does not support != operator");
			};
		}

		private static int cmpOpToVecCmpSubop(DecimalBoundaryFolder.CmpOp op) {
			return switch (op) {
				case EQ -> ImpulseIsaTables.SUBOP_VEC_CMP_EQ;
				case NE -> ImpulseIsaTables.SUBOP_VEC_CMP_NE;
				case LT -> ImpulseIsaTables.SUBOP_VEC_CMP_LT;
				case LE -> ImpulseIsaTables.SUBOP_VEC_CMP_LE;
				case GT -> ImpulseIsaTables.SUBOP_VEC_CMP_GT;
				case GE -> ImpulseIsaTables.SUBOP_VEC_CMP_GE;
			};
		}

		private static DecimalBoundaryFolder.CmpOp flipCmpOp(DecimalBoundaryFolder.CmpOp op) {
			return switch (op) {
				case LT -> DecimalBoundaryFolder.CmpOp.GT;
				case LE -> DecimalBoundaryFolder.CmpOp.GE;
				case GT -> DecimalBoundaryFolder.CmpOp.LT;
				case GE -> DecimalBoundaryFolder.CmpOp.LE;
				case EQ, NE -> op;
			};
		}

		private TypedVal emitFoldedVecCmp(DecimalBoundaryFolder.CmpOp op, TypedVal vecDec, TypedVal constLit,
				int outDom) {
			DecimalBoundaryFolder.FoldedComparison fc = DecimalBoundaryFolder.foldComparison(op, vecDec.precision,
					vecDec.scale, constLit.constVal, constLit.scale);
			if (fc.kind() == DecimalBoundaryFolder.FoldKind.COMPARE) {
				int rThresh;
				if (constLit.reg >= 0 && fc.thresholdUnscaled() == constLit.constVal) {
					rThresh = constLit.reg;
				} else {
					rThresh = allocR();
					b.emitLoadConstI64(rThresh, fc.thresholdUnscaled());
				}
				int dst = allocV();
				b.emit(ImpulseIsaTables.OP_VEC_CMP, cmpOpToVecCmpSubop(fc.op()), ImpulseIsaTables.FLAG_SCALAR_RHS, dst,
						vecDec.reg, rThresh, 0, 0);
				return TypedVal.vec(ValBank.VEC_BOOL, dst, outDom);
			}
			int dst = allocV();
			if (fc.kind() == DecimalBoundaryFolder.FoldKind.ALWAYS_FALSE) {
				// All-false for valid lanes, NULL for NULL lanes: vecDec != vecDec
				b.emit(ImpulseIsaTables.OP_VEC_CMP, ImpulseIsaTables.SUBOP_VEC_CMP_NE, 0, dst, vecDec.reg, vecDec.reg,
						0, 0);
			} else {
				// All-true for valid lanes, NULL for NULL lanes: vecDec == vecDec
				b.emit(ImpulseIsaTables.OP_VEC_CMP, ImpulseIsaTables.SUBOP_VEC_CMP_EQ, 0, dst, vecDec.reg, vecDec.reg,
						0, 0);
			}
			return TypedVal.vec(ValBank.VEC_BOOL, dst, outDom);
		}

		private TypedVal evalComparison(String head, TypedVal lhs, TypedVal rhs) {
			int savedPinR = pinR(lhs);
			int savedPinV = pinV(lhs);
			pinR(rhs);
			pinV(rhs);
			try {
				DecimalBoundaryFolder.CmpOp cmpOp = parseCmpOp(head);
				boolean anyDec = lhs.bank.isDecimal() || rhs.bank.isDecimal();
				if (anyDec) {
					TypedVal dl = ensureDecimal(lhs, head);
					TypedVal dr = ensureDecimal(rhs, head);
					boolean lVec = dl.bank.isVec();
					boolean rVec = dr.bank.isVec();
					int outDom = lVec ? dl.domId : (rVec ? dr.domId : 0xFFFF);

					// Compile-time boundary folding (both vec <op> lit AND symmetric lit <op> vec)
					if (lVec && !rVec && dr.isConst) {
						return emitFoldedVecCmp(cmpOp, dl, dr, outDom);
					}
					if (!lVec && rVec && dl.isConst) {
						return emitFoldedVecCmp(flipCmpOp(cmpOp), dr, dl, outDom);
					}

					int s = Math.max(dl.scale, dr.scale);
					TypedVal a = rescaleDecimalUp(dl, s);
					pinR(a);
					pinV(a);
					TypedVal bVal = rescaleDecimalUp(dr, s);
					pinR(bVal);
					pinV(bVal);

					if (!lVec && !rVec) {
						int dst = allocR();
						emitScalarI64Cmp(cmpOp, dst, a.reg, bVal.reg);
						return TypedVal.scalar(ValBank.SCALAR_BOOL, dst);
					}
					if (lVec && rVec) {
						int dst = allocV();
						b.emit(ImpulseIsaTables.OP_VEC_CMP, cmpOpToVecCmpSubop(cmpOp), 0, dst, a.reg, bVal.reg, 0, 0);
						return TypedVal.vec(ValBank.VEC_BOOL, dst, outDom);
					}
					if (lVec) {
						int dst = allocV();
						b.emit(ImpulseIsaTables.OP_VEC_CMP, cmpOpToVecCmpSubop(cmpOp), ImpulseIsaTables.FLAG_SCALAR_RHS,
								dst, a.reg, bVal.reg, 0, 0);
						return TypedVal.vec(ValBank.VEC_BOOL, dst, outDom);
					}
					int dst = allocV();
					b.emit(ImpulseIsaTables.OP_VEC_CMP, cmpOpToVecCmpSubop(flipCmpOp(cmpOp)),
							ImpulseIsaTables.FLAG_SCALAR_RHS, dst, bVal.reg, a.reg, 0, 0);
					return TypedVal.vec(ValBank.VEC_BOOL, dst, outDom);
				}

				// Non-decimal comparisons
				if ((lhs.bank.isInt() && rhs.bank.isFloat()) || (lhs.bank.isFloat() && rhs.bank.isInt())) {
					throw new IllegalArgumentException(
							"TYPE_MISMATCH: implicit int/float comparison forbidden; use (cast ...)");
				}
				if (!lhs.bank.isVec() && !rhs.bank.isVec()) {
					int dst = allocR();
					if (lhs.bank.isFloat()) {
						emitScalarF64Cmp(cmpOp, dst, lhs.reg, rhs.reg);
					} else {
						emitScalarI64Cmp(cmpOp, dst, lhs.reg, rhs.reg);
					}
					return TypedVal.scalar(ValBank.SCALAR_BOOL, dst);
				}
				int outDom = lhs.bank.isVec() ? lhs.domId : rhs.domId;
				int dst = allocV();
				if (lhs.bank.isVec() && rhs.bank.isVec()) {
					b.emit(ImpulseIsaTables.OP_VEC_CMP, cmpOpToVecCmpSubop(cmpOp), 0, dst, lhs.reg, rhs.reg, 0, 0);
				} else if (lhs.bank.isVec()) {
					b.emit(ImpulseIsaTables.OP_VEC_CMP, cmpOpToVecCmpSubop(cmpOp), ImpulseIsaTables.FLAG_SCALAR_RHS,
							dst, lhs.reg, rhs.reg, 0, 0);
				} else {
					b.emit(ImpulseIsaTables.OP_VEC_CMP, cmpOpToVecCmpSubop(flipCmpOp(cmpOp)),
							ImpulseIsaTables.FLAG_SCALAR_RHS, dst, rhs.reg, lhs.reg, 0, 0);
				}
				return TypedVal.vec(ValBank.VEC_BOOL, dst, outDom);
			} finally {
				pinnedRMask = savedPinR;
				pinnedVMask = savedPinV;
			}
		}

		private void emitScalarI64Cmp(DecimalBoundaryFolder.CmpOp op, int dst, int a, int bReg) {
			switch (op) {
				case EQ -> b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_CMP_EQ_I64, 0, dst,
						a, bReg, 0, 0);
				case NE -> b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_CMP_NE_I64, 0, dst,
						a, bReg, 0, 0);
				case LT -> b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_CMP_LT_I64, 0, dst,
						a, bReg, 0, 0);
				case LE -> b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_CMP_LE_I64, 0, dst,
						a, bReg, 0, 0);
				case GT -> b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_CMP_LT_I64, 0, dst,
						bReg, a, 0, 0);
				case GE -> b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_CMP_LE_I64, 0, dst,
						bReg, a, 0, 0);
			}
		}

		private void emitScalarF64Cmp(DecimalBoundaryFolder.CmpOp op, int dst, int a, int bReg) {
			switch (op) {
				case EQ -> b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_CMP_EQ_F64, 0, dst,
						a, bReg, 0, 0);
				case NE -> b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_CMP_NE_F64, 0, dst,
						a, bReg, 0, 0);
				case LT -> b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_CMP_LT_F64, 0, dst,
						a, bReg, 0, 0);
				case LE -> b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_CMP_LE_F64, 0, dst,
						a, bReg, 0, 0);
				case GT -> b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_CMP_LT_F64, 0, dst,
						bReg, a, 0, 0);
				case GE -> b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_CMP_LE_F64, 0, dst,
						bReg, a, 0, 0);
			}
		}

		private static boolean sexprMentionsSymbol(SExpr e, String sym) {
			if (e == null) {
				return false;
			}
			if (e.kind == Kind.SYMBOL && sym.equals(e.text)) {
				return true;
			}
			if (e.kind == Kind.LIST && e.elems != null) {
				for (SExpr child : e.elems) {
					if (sexprMentionsSymbol(child, sym)) {
						return true;
					}
				}
			}
			return false;
		}

		private TypedVal evalNodeFilter(SExpr e) {
			// Form 1: (node-filter f "attr" op val)
			// Form 2: (node-filter f (lambda (u) <expr>))
			TypedVal srcF = eval(e.elems.get(1));
			if (srcF.bank != ValBank.FRONTIER) {
				throw new IllegalArgumentException("TYPE_MISMATCH: node-filter expects frontier");
			}
			String attrName = null;
			String opStr = null;
			SExpr rhsExpr = null;
			boolean fastPath = false;
			String lamVar = null;
			SExpr lamBody = null;

			if (e.elems.size() == 5 && e.elems.get(2).kind == Kind.STRING) {
				attrName = e.elems.get(2).text;
				opStr = e.elems.get(3).text;
				rhsExpr = e.elems.get(4);
				fastPath = true;
			} else if (e.elems.size() == 3 && e.elems.get(2).kind == Kind.LIST
					&& "lambda".equals(e.elems.get(2).elems.get(0).text)) {
				SExpr lam = e.elems.get(2);
				if (lam.elems.size() < 3 || lam.elems.get(1).kind != Kind.LIST || lam.elems.get(1).elems.isEmpty()) {
					throw new IllegalArgumentException("PARSE_ERROR: malformed node-filter lambda");
				}
				lamVar = lam.elems.get(1).elems.get(0).text;
				lamBody = lam.elems.get(2);
				// Fast OP_NODE_FILTER pushdown for simple (cmpOp (attr u "name") scalarRhs)
				if (lamBody.kind == Kind.LIST && lamBody.elems.size() == 3
						&& parseCmpOp(lamBody.elems.get(0).text) != null) {
					SExpr lhsForm = lamBody.elems.get(1);
					SExpr candRhs = lamBody.elems.get(2);
					if (lhsForm.kind == Kind.LIST && lhsForm.elems.size() == 3
							&& "attr".equals(lhsForm.elems.get(0).text) && lamVar.equals(lhsForm.elems.get(1).text)
							&& lhsForm.elems.get(2).kind == Kind.STRING && !sexprMentionsSymbol(candRhs, lamVar)) {
						attrName = lhsForm.elems.get(2).text;
						opStr = lamBody.elems.get(0).text;
						rhsExpr = candRhs;
						fastPath = true;
					}
				}
			} else {
				throw new IllegalArgumentException("PARSE_ERROR: malformed node-filter");
			}

			if (!fastPath) {
				// General fallback: evaluate lambda body over domain vectors -> BOOL_3VL ->
				// OP_SET_CONVERT.b2f -> OP_SET_OP.intersect with srcF
				String prevVar = this.nodeFilterVar;
				int prevDom = this.nodeFilterDom;
				this.nodeFilterVar = lamVar;
				this.nodeFilterDom = srcF.domId;
				TypedVal predVec;
				try {
					predVec = eval(lamBody);
				} finally {
					this.nodeFilterVar = prevVar;
					this.nodeFilterDom = prevDom;
				}
				if (predVec.bank != ValBank.VEC_BOOL) {
					throw new IllegalArgumentException(
							"TYPE_MISMATCH: node-filter predicate must evaluate to BOOL vector, got " + predVec.bank);
				}
				int fPred = allocF();
				b.emitU16(ImpulseIsaTables.OP_SET_CONVERT, ImpulseIsaTables.SUBOP_SET_CONVERT_B2F, 0, fPred,
						predVec.reg, 0, 0, srcF.domId, 0);
				int dstF = allocF();
				b.emit(ImpulseIsaTables.OP_SET_OP, ImpulseIsaTables.SUBOP_SET_OP_INTERSECT, 0, dstF, srcF.reg, fPred, 0,
						0);
				return TypedVal.frontier(dstF, srcF.domId);
			}

			DecimalBoundaryFolder.CmpOp cmpOp = parseCmpOp(opStr);
			if (cmpOp == null) {
				throw new IllegalArgumentException("PARSE_ERROR: unsupported node-filter op '" + opStr + "'");
			}
			AttrInfo ai = resolveNodeAttr(srcF.domId, attrName);
			TypedVal rhsVal = eval(rhsExpr);
			if (rhsVal.bank.isVec()) {
				// If RHS evaluated to a vector (e.g. a WITH alias), use general vector path
				String prevVar = this.nodeFilterVar;
				int prevDom = this.nodeFilterDom;
				this.nodeFilterVar = lamVar != null ? lamVar : "__u";
				this.nodeFilterDom = srcF.domId;
				TypedVal colVec;
				try {
					int vCol = allocV();
					b.emitU16(ImpulseIsaTables.OP_LOAD_COLUMN, ImpulseIsaTables.SUBOP_LOAD_COLUMN_NODE_ATTR, 0, vCol, 0,
							0, 0, srcF.domId, ai.attrId);
					colVec = ai.isDecimal()
							? TypedVal.vecDecimal(vCol, srcF.domId, ai.precision, ai.scale)
							: TypedVal.vec(attrTypeToVecBank(ai), vCol, srcF.domId);
				} finally {
					this.nodeFilterVar = prevVar;
					this.nodeFilterDom = prevDom;
				}
				TypedVal predVec = evalComparison(opStr, colVec, rhsVal);
				int fPred = allocF();
				b.emitU16(ImpulseIsaTables.OP_SET_CONVERT, ImpulseIsaTables.SUBOP_SET_CONVERT_B2F, 0, fPred,
						predVec.reg, 0, 0, srcF.domId, 0);
				int dstF = allocF();
				b.emit(ImpulseIsaTables.OP_SET_OP, ImpulseIsaTables.SUBOP_SET_OP_INTERSECT, 0, dstF, srcF.reg, fPred, 0,
						0);
				return TypedVal.frontier(dstF, srcF.domId);
			}
			int dstF = allocF();

			if (ai.isDecimal() || rhsVal.bank.isDecimal()) {
				int colPrec;
				int colScale;
				if (ai.isDecimal()) {
					colPrec = ai.precision;
					colScale = ai.scale;
				} else {
					ValBank colBank = attrTypeToVecBank(ai);
					if (!colBank.isInt()) {
						throw new IllegalArgumentException("TYPE_MISMATCH: cannot compare non-decimal column '"
								+ attrName + "' (" + colBank + ") with DECIMAL; use (cast ...)");
					}
					// Exact promotion of INT column to DECIMAL(18, 0)
					colPrec = 18;
					colScale = 0;
				}
				TypedVal decRhs = ensureDecimal(rhsVal, opStr);
				if (decRhs.isConst) {
					// Compile-time boundary folding: zero runtime rescale!
					DecimalBoundaryFolder.FoldedComparison fc = DecimalBoundaryFolder.foldComparison(cmpOp, colPrec,
							colScale, decRhs.constVal, decRhs.scale);
					if (fc.kind() == DecimalBoundaryFolder.FoldKind.ALWAYS_FALSE) {
						b.emitU16(ImpulseIsaTables.OP_FSEED, ImpulseIsaTables.SUBOP_FSEED_EMPTY, 0, dstF, 0, 0, 0,
								srcF.domId, 0);
						return TypedVal.frontier(dstF, srcF.domId);
					}
					if (fc.kind() == DecimalBoundaryFolder.FoldKind.ALWAYS_TRUE) {
						b.emitU16(ImpulseIsaTables.OP_NODE_FILTER, ImpulseIsaTables.SUBOP_NODE_FILTER_IS_NOT_NULL, 0,
								dstF, srcF.reg, 0, 0, srcF.domId, ai.attrId);
						return TypedVal.frontier(dstF, srcF.domId);
					}
					int rThresh = allocR();
					b.emitLoadConstI64(rThresh, fc.thresholdUnscaled());
					b.emitU16(ImpulseIsaTables.OP_NODE_FILTER, cmpOpToNodeFilterSubop(fc.op()),
							ImpulseIsaTables.FLAG_SCALAR_RHS, dstF, srcF.reg, rThresh, 0, srcF.domId, ai.attrId);
					return TypedVal.frontier(dstF, srcF.domId);
				}
				// Dynamic scalar decimal param: rescale if decRhs.scale < colScale
				int rCmp = decRhs.reg;
				if (decRhs.scale < colScale) {
					rCmp = rescaleDecimalUp(decRhs, colScale).reg;
				} else if (decRhs.scale > colScale) {
					throw new IllegalArgumentException("TYPE_MISMATCH: dynamic node-filter parameter scale ("
							+ decRhs.scale + ") exceeds column scale (" + colScale + "); cast parameter first");
				}
				b.emitU16(ImpulseIsaTables.OP_NODE_FILTER, cmpOpToNodeFilterSubop(cmpOp),
						ImpulseIsaTables.FLAG_SCALAR_RHS, dstF, srcF.reg, rCmp, 0, srcF.domId, ai.attrId);
				return TypedVal.frontier(dstF, srcF.domId);
			}

			ValBank colBank = attrTypeToVecBank(ai);
			if ((colBank.isInt() && rhsVal.bank.isFloat()) || (colBank.isFloat() && rhsVal.bank.isInt())) {
				throw new IllegalArgumentException(
						"TYPE_MISMATCH: implicit int/float node-filter comparison forbidden; use (cast ...)");
			}
			b.emitU16(ImpulseIsaTables.OP_NODE_FILTER, cmpOpToNodeFilterSubop(cmpOp), ImpulseIsaTables.FLAG_SCALAR_RHS,
					dstF, srcF.reg, rhsVal.reg, 0, srcF.domId, ai.attrId);
			return TypedVal.frontier(dstF, srcF.domId);
		}

		private TypedVal evalIndexScan(SExpr e) {
			// (index-scan "Domain" "attr" op lo [hi])
			if (e.elems.size() < 5 || e.elems.size() > 6) {
				throw new IllegalArgumentException("PARSE_ERROR: (index-scan \"Domain\" \"attr\" op lo [hi]) expected");
			}
			int domId = resolveDomain(e.elems.get(1).text);
			String attrName = e.elems.get(2).text;
			AttrInfo ai = resolveNodeAttr(domId, attrName);
			String opStr = e.elems.get(3).text;
			boolean isBetweenCC = "between".equals(opStr) || "between_cc".equals(opStr);
			boolean isBetweenCO = "between-co".equals(opStr) || "between_co".equals(opStr);
			int dstF = allocF();

			if (isBetweenCC || isBetweenCO) {
				if (e.elems.size() != 6) {
					throw new IllegalArgumentException("PARSE_ERROR: index-scan " + opStr + " requires lo and hi");
				}
				TypedVal loVal = eval(e.elems.get(4));
				int savedPin = pinR(loVal);
				TypedVal hiVal;
				try {
					hiVal = eval(e.elems.get(5));
				} finally {
					pinnedRMask = savedPin;
				}
				if (ai.isDecimal() || loVal.bank.isDecimal() || hiVal.bank.isDecimal()) {
					int colPrec = ai.isDecimal() ? ai.precision : 18;
					int colScale = ai.isDecimal() ? ai.scale : 0;
					TypedVal decLo = ensureDecimal(loVal, opStr);
					TypedVal decHi = ensureDecimal(hiVal, opStr);
					int rLo;
					if (decLo.isConst) {
						DecimalBoundaryFolder.FoldedComparison fcLo = DecimalBoundaryFolder.foldComparison(
								DecimalBoundaryFolder.CmpOp.GE, colPrec, colScale, decLo.constVal, decLo.scale);
						if (fcLo.kind() == DecimalBoundaryFolder.FoldKind.ALWAYS_FALSE) {
							b.emitU16(ImpulseIsaTables.OP_FSEED, ImpulseIsaTables.SUBOP_FSEED_EMPTY, 0, dstF, 0, 0, 0,
									domId, 0);
							return TypedVal.frontier(dstF, domId);
						}
						long loThresh = (fcLo.kind() == DecimalBoundaryFolder.FoldKind.ALWAYS_TRUE)
								? -(Decimal64.POW10[colPrec] - 1L)
								: fcLo.thresholdUnscaled();
						if (decLo.reg >= 0 && loThresh == decLo.constVal) {
							rLo = decLo.reg;
						} else {
							rLo = allocR();
							b.emitLoadConstI64(rLo, loThresh);
						}
					} else {
						if (decLo.scale > colScale) {
							throw new IllegalArgumentException("TYPE_MISMATCH: dynamic index-scan lo scale ("
									+ decLo.scale + ") exceeds column scale (" + colScale + ")");
						}
						rLo = (decLo.scale < colScale) ? rescaleDecimalUp(decLo, colScale).reg : decLo.reg;
					}
					int savedPinLo = pinnedRMask;
					pinnedRMask |= (1 << rLo);
					int rHi;
					int subop = isBetweenCC
							? ImpulseIsaTables.SUBOP_INDEX_SCAN_BETWEEN_CC
							: ImpulseIsaTables.SUBOP_INDEX_SCAN_BETWEEN_CO;
					try {
						if (decHi.isConst) {
							DecimalBoundaryFolder.CmpOp hiCmp = isBetweenCC
									? DecimalBoundaryFolder.CmpOp.LE
									: DecimalBoundaryFolder.CmpOp.LT;
							DecimalBoundaryFolder.FoldedComparison fcHi = DecimalBoundaryFolder.foldComparison(hiCmp,
									colPrec, colScale, decHi.constVal, decHi.scale);
							if (fcHi.kind() == DecimalBoundaryFolder.FoldKind.ALWAYS_FALSE) {
								b.emitU16(ImpulseIsaTables.OP_FSEED, ImpulseIsaTables.SUBOP_FSEED_EMPTY, 0, dstF, 0, 0,
										0, domId, 0);
								return TypedVal.frontier(dstF, domId);
							}
							long hiThresh;
							if (fcHi.kind() == DecimalBoundaryFolder.FoldKind.ALWAYS_TRUE) {
								hiThresh = Decimal64.POW10[colPrec] - 1L;
								subop = ImpulseIsaTables.SUBOP_INDEX_SCAN_BETWEEN_CC;
							} else {
								hiThresh = fcHi.thresholdUnscaled();
								subop = (fcHi.op() == DecimalBoundaryFolder.CmpOp.LT)
										? ImpulseIsaTables.SUBOP_INDEX_SCAN_BETWEEN_CO
										: ImpulseIsaTables.SUBOP_INDEX_SCAN_BETWEEN_CC;
							}
							if (decHi.reg >= 0 && hiThresh == decHi.constVal) {
								rHi = decHi.reg;
							} else {
								rHi = allocR();
								b.emitLoadConstI64(rHi, hiThresh);
							}
						} else {
							if (decHi.scale > colScale) {
								throw new IllegalArgumentException("TYPE_MISMATCH: dynamic index-scan hi scale ("
										+ decHi.scale + ") exceeds column scale (" + colScale + ")");
							}
							rHi = (decHi.scale < colScale) ? rescaleDecimalUp(decHi, colScale).reg : decHi.reg;
						}
					} finally {
						pinnedRMask = savedPinLo;
					}
					b.emitU16(ImpulseIsaTables.OP_INDEX_SCAN, subop, 0, dstF, 0, rLo, rHi, domId, ai.attrId);
					return TypedVal.frontier(dstF, domId);
				}
				int subop = isBetweenCC
						? ImpulseIsaTables.SUBOP_INDEX_SCAN_BETWEEN_CC
						: ImpulseIsaTables.SUBOP_INDEX_SCAN_BETWEEN_CO;
				b.emitU16(ImpulseIsaTables.OP_INDEX_SCAN, subop, 0, dstF, 0, loVal.reg, hiVal.reg, domId, ai.attrId);
				return TypedVal.frontier(dstF, domId);
			}

			DecimalBoundaryFolder.CmpOp cmpOp = parseCmpOp(opStr);
			if (cmpOp == null || cmpOp == DecimalBoundaryFolder.CmpOp.NE) {
				throw new IllegalArgumentException("PARSE_ERROR: unsupported index-scan op '" + opStr + "'");
			}
			TypedVal loVal = eval(e.elems.get(4));
			if (ai.isDecimal() || loVal.bank.isDecimal()) {
				int colPrec;
				int colScale;
				if (ai.isDecimal()) {
					colPrec = ai.precision;
					colScale = ai.scale;
				} else {
					ValBank colBank = attrTypeToVecBank(ai);
					if (!colBank.isInt()) {
						throw new IllegalArgumentException("TYPE_MISMATCH: cannot index-scan non-decimal column '"
								+ attrName + "' (" + colBank + ") with DECIMAL; use (cast ...)");
					}
					colPrec = 18;
					colScale = 0;
				}
				TypedVal decLo = ensureDecimal(loVal, opStr);
				if (decLo.isConst) {
					DecimalBoundaryFolder.FoldedComparison fc = DecimalBoundaryFolder.foldComparison(cmpOp, colPrec,
							colScale, decLo.constVal, decLo.scale);
					if (fc.kind() == DecimalBoundaryFolder.FoldKind.ALWAYS_FALSE) {
						b.emitU16(ImpulseIsaTables.OP_FSEED, ImpulseIsaTables.SUBOP_FSEED_EMPTY, 0, dstF, 0, 0, 0,
								domId, 0);
						return TypedVal.frontier(dstF, domId);
					}
					if (fc.kind() == DecimalBoundaryFolder.FoldKind.ALWAYS_TRUE) {
						int fAll = allocF();
						b.emitU16(ImpulseIsaTables.OP_INIT_DOMAIN_SPAN,
								ImpulseIsaTables.SUBOP_INIT_DOMAIN_SPAN_ALL_SPAN, 0, fAll, 0, 0, 0, domId, 0);
						b.emitU16(ImpulseIsaTables.OP_NODE_FILTER, ImpulseIsaTables.SUBOP_NODE_FILTER_IS_NOT_NULL, 0,
								dstF, fAll, 0, 0, domId, ai.attrId);
						return TypedVal.frontier(dstF, domId);
					}
					int rThresh;
					if (decLo.reg >= 0 && fc.thresholdUnscaled() == decLo.constVal) {
						rThresh = decLo.reg;
					} else {
						rThresh = allocR();
						b.emitLoadConstI64(rThresh, fc.thresholdUnscaled());
					}
					b.emitU16(ImpulseIsaTables.OP_INDEX_SCAN, cmpOpToIndexScanSubop(fc.op()), 0, dstF, 0, rThresh, 0,
							domId, ai.attrId);
					return TypedVal.frontier(dstF, domId);
				}
				int rCmp = decLo.reg;
				if (decLo.scale < colScale) {
					rCmp = rescaleDecimalUp(decLo, colScale).reg;
				} else if (decLo.scale > colScale) {
					throw new IllegalArgumentException("TYPE_MISMATCH: dynamic index-scan parameter scale ("
							+ decLo.scale + ") exceeds column scale (" + colScale + "); cast parameter first");
				}
				b.emitU16(ImpulseIsaTables.OP_INDEX_SCAN, cmpOpToIndexScanSubop(cmpOp), 0, dstF, 0, rCmp, 0, domId,
						ai.attrId);
				return TypedVal.frontier(dstF, domId);
			}

			ValBank colBank = attrTypeToVecBank(ai);
			if ((colBank.isInt() && loVal.bank.isFloat()) || (colBank.isFloat() && loVal.bank.isInt())) {
				throw new IllegalArgumentException(
						"TYPE_MISMATCH: implicit int/float index-scan comparison forbidden; use (cast ...)");
			}
			b.emitU16(ImpulseIsaTables.OP_INDEX_SCAN, cmpOpToIndexScanSubop(cmpOp), 0, dstF, 0, loVal.reg, 0, domId,
					ai.attrId);
			return TypedVal.frontier(dstF, domId);
		}

		private TypedVal evalEdgeStream(SExpr e) {
			// (edge-stream :fwd|:rev "REL" f (lambda (src tgt edge) stmt...))
			String dir = e.elems.get(1).text;
			String relName = e.elems.get(2).text;
			RelInfo rel = resolveRel(relName);
			TypedVal srcF = eval(e.elems.get(3));
			if (srcF.bank != ValBank.FRONTIER) {
				throw new IllegalArgumentException("TYPE_MISMATCH: edge-stream expects frontier");
			}
			int expectedSrc = ":rev".equals(dir) ? rel.tgtDom : rel.srcDom;
			if (srcF.domId != expectedSrc) {
				throw new IllegalArgumentException(
						"DOMAIN_MISMATCH: edge-stream source frontier domain mismatch on '" + relName + "'");
			}
			SExpr lam = e.elems.get(4);
			if (lam.kind != Kind.LIST || lam.elems.size() < 3 || !"lambda".equals(lam.elems.get(0).text)) {
				throw new IllegalArgumentException("PARSE_ERROR: edge-stream expects (lambda (src tgt edge) ...)");
			}
			List<SExpr> params = lam.elems.get(1).elems;
			if (params.size() != 3) {
				throw new IllegalArgumentException("PARSE_ERROR: edge-stream lambda requires 3 params (src tgt edge)");
			}
			String srcVar = params.get(0).text;
			String tgtVar = params.get(1).text;
			String edgeVar = params.get(2).text;

			int wSub = ":rev".equals(dir)
					? ImpulseIsaTables.SUBOP_WALK_MORSEL_REV
					: ImpulseIsaTables.SUBOP_WALK_MORSEL_FWD;
			int startPc = b.emitU16(ImpulseIsaTables.OP_WALK_MORSEL, wSub, 0, 0, srcF.reg, 0, 0, rel.relId, 0);

			MorselScope prevMorsel = this.morsel;
			this.morsel = new MorselScope(rel, srcVar, tgtVar, edgeVar);
			TypedVal result = srcF;
			try {
				for (int i = 2; i < lam.elems.size(); i++) {
					SExpr stmt = lam.elems.get(i);
					if (stmt.kind != Kind.LIST || stmt.elems.isEmpty()) {
						throw new IllegalArgumentException("PARSE_ERROR: malformed edge-stream statement");
					}
					String sh = stmt.elems.get(0).text;
					if ("where".equals(sh)) {
						TypedVal pred = eval(stmt.elems.get(1));
						if (pred.bank != ValBank.VEC_BOOL) {
							throw new IllegalArgumentException("TYPE_MISMATCH: where requires bool vector predicate");
						}
						// Convert 3VL bool vector into morsel frontier register F9 (or intersect F9 &
						// F10 if multiple where clauses appear in one edge-stream)
						if (this.morsel.predReg < 0) {
							b.emit(ImpulseIsaTables.OP_SET_CONVERT, ImpulseIsaTables.SUBOP_SET_CONVERT_B2F, 0, 9,
									pred.reg, 0, 0, 0);
							this.morsel.predReg = 9;
						} else {
							b.emit(ImpulseIsaTables.OP_SET_CONVERT, ImpulseIsaTables.SUBOP_SET_CONVERT_B2F, 0, 10,
									pred.reg, 0, 0, 0);
							b.emit(ImpulseIsaTables.OP_SET_OP, ImpulseIsaTables.SUBOP_SET_OP_INTERSECT, 0, 9, 9, 10, 0,
									0);
						}
					} else if ("scatter-add".equals(sh)) {
						// (scatter-add out_vec tgt val_expr)
						TypedVal outVec = eval(stmt.elems.get(1));
						String idxSym = stmt.elems.get(2).text;
						int idxReg = idxSym.equals(tgtVar) ? 9 : (idxSym.equals(srcVar) ? 8 : -1);
						if (idxReg < 0) {
							throw new IllegalArgumentException(
									"PARSE_ERROR: scatter-add target must be src or tgt cursor");
						}
						TypedVal valExpr = eval(stmt.elems.get(3));
						int flags = (this.morsel.predReg >= 0) ? ImpulseIsaTables.FLAG_MASKED_S3 : 0;
						int predR = (this.morsel.predReg >= 0) ? this.morsel.predReg : 0;
						if (outVec.bank.isDecimal() || valExpr.bank.isDecimal()) {
							TypedVal decVal = ensureDecimal(valExpr, "scatter-add");
							if (!outVec.bank.isDecimal()) {
								throw new IllegalArgumentException(
										"TYPE_MISMATCH: scatter-add decimal value into non-decimal accumulator");
							}
							if (decVal.scale < outVec.scale) {
								decVal = rescaleDecimalUp(decVal, outVec.scale);
							} else if (decVal.scale > outVec.scale) {
								// Cast/round down to accumulator scale
								int vRound = allocV();
								b.emitVecDecRoundScale(vRound, decVal.reg, decVal.scale - outVec.scale, 0, 0);
								decVal = TypedVal.vecDecimal(vRound, outVec.domId, outVec.precision, outVec.scale);
							}
							b.emitMorselCommitScatterAddChk(outVec.reg, idxReg, decVal.reg, flags, predR);
						} else {
							b.emit(ImpulseIsaTables.OP_MORSEL_COMMIT, ImpulseIsaTables.SUBOP_MORSEL_COMMIT_SCATTER_ADD,
									flags, outVec.reg, idxReg, valExpr.reg, predR, 0);
						}
						result = outVec;
					} else if ("yield-frontier".equals(sh)) {
						TypedVal outF = eval(stmt.elems.get(1));
						String idxSym = stmt.elems.get(2).text;
						int idxReg = idxSym.equals(tgtVar) ? 9 : 8;
						int flags = (this.morsel.predReg >= 0) ? ImpulseIsaTables.FLAG_MASKED_S3 : 0;
						int predR = (this.morsel.predReg >= 0) ? this.morsel.predReg : 0;
						b.emit(ImpulseIsaTables.OP_MORSEL_COMMIT, ImpulseIsaTables.SUBOP_MORSEL_COMMIT_YIELD_FRONTIER,
								flags, outF.reg, idxReg, 0, predR, 0);
						result = outF;
					} else {
						result = eval(stmt);
					}
				}
			} finally {
				this.morsel = prevMorsel;
			}
			int endPc = b.pc();
			int bodyLen = endPc - startPc;
			b.patchImm16Hi(startPc, bodyLen);
			b.emitU16(ImpulseIsaTables.OP_MORSEL_END, 0, 0, 0, 0, 0, 0, rel.relId, bodyLen);
			return result;
		}

		TypedVal eval(SExpr e) {
			if (e.kind == Kind.INT) {
				int r = allocR();
				b.emitLoadConstI64(r, e.iVal);
				return TypedVal.constInt(r, e.iVal);
			}
			if (e.kind == Kind.FLOAT) {
				int r = allocR();
				b.emitLoadConstF64(r, e.fVal);
				return TypedVal.scalar(ValBank.SCALAR_F64, r);
			}
			if (e.kind == Kind.BOOL) {
				int r = allocR();
				b.emitLoadConstI64(r, e.bVal ? 1L : 0L);
				return TypedVal.scalar(ValBank.SCALAR_BOOL, r);
			}
			if (e.kind == Kind.STRING) {
				int r = allocR();
				int cpId = b.addConstPoolStr(e.text);
				b.emit(ImpulseIsaTables.OP_LOAD_CONST_POOL, ImpulseIsaTables.SUBOP_LOAD_CONST_POOL_STR, 0, r, 0, 0, 0,
						cpId);
				return TypedVal.scalar(ValBank.SCALAR_STR, r);
			}
			if (e.kind == Kind.NULL) {
				int r = allocR();
				b.emit(ImpulseIsaTables.OP_LOAD_CONST, ImpulseIsaTables.SUBOP_LOAD_CONST_NULL, 0, r, 0, 0, 0, 0);
				return TypedVal.scalar(ValBank.SCALAR_I64, r);
			}
			if (e.kind == Kind.SYMBOL || e.kind == Kind.PARAM) {
				TypedVal v = env.get(e.text);
				if (v == null)
					throw new IllegalArgumentException("UNBOUND_SYMBOL: '" + e.text + "'");
				return v;
			}
			if (e.kind != Kind.LIST || e.elems.isEmpty() || e.elems.get(0).kind != Kind.SYMBOL) {
				throw new IllegalArgumentException("PARSE_ERROR: expected form");
			}
			String head = e.elems.get(0).text;

			// Exact decimal literal: (dec "123.45" P S)
			if ("dec".equals(head)) {
				if (e.elems.size() != 4 || e.elems.get(1).kind != Kind.STRING || e.elems.get(2).kind != Kind.INT
						|| e.elems.get(3).kind != Kind.INT) {
					throw new IllegalArgumentException("PARSE_ERROR: (dec \"digits\" P S) expected");
				}
				long pRaw = e.elems.get(2).iVal;
				long sRaw = e.elems.get(3).iVal;
				if (pRaw < 1L || pRaw > 18L || sRaw < 0L || sRaw > pRaw) {
					throw new IllegalArgumentException("PARSE_ERROR: invalid DECIMAL(" + pRaw + ", " + sRaw + ")");
				}
				int p = (int) pRaw;
				int s = (int) sRaw;
				long unscaled = Decimal64.parseUnscaledExact(e.elems.get(1).text, p, s);
				int r = allocR();
				b.emitLoadConstI64(r, unscaled);
				return TypedVal.constDecimal(r, p, s, unscaled);
			}

			if ("let".equals(head)) {
				if (e.elems.size() < 3 || e.elems.get(1).kind != Kind.LIST)
					throw new IllegalArgumentException("PARSE_ERROR: malformed let");
				Map<String, TypedVal> saved = new HashMap<>(env);
				for (SExpr bind : e.elems.get(1).elems) {
					if (bind.kind != Kind.LIST || bind.elems.size() != 2 || bind.elems.get(0).kind != Kind.SYMBOL)
						throw new IllegalArgumentException("PARSE_ERROR: malformed let binding");
					env.put(bind.elems.get(0).text, eval(bind.elems.get(1)));
				}
				TypedVal last = null;
				for (int i = 2; i < e.elems.size(); i++)
					last = eval(e.elems.get(i));
				env = saved;
				return last;
			}
			if ("domain-all".equals(head)) {
				int dom = resolveDomain(e.elems.get(1).text);
				int dst = allocF();
				b.emitU16(ImpulseIsaTables.OP_INIT_DOMAIN_SPAN, ImpulseIsaTables.SUBOP_INIT_DOMAIN_SPAN_ALL_SPAN, 0,
						dst, 0, 0, 0, dom, 0);
				return TypedVal.frontier(dst, dom);
			}
			if ("seed".equals(head)) {
				int dom = resolveDomain(e.elems.get(1).text);
				int dst = allocF();
				if (e.elems.size() == 2) {
					b.emitU16(ImpulseIsaTables.OP_FSEED, ImpulseIsaTables.SUBOP_FSEED_EMPTY, 0, dst, 0, 0, 0, dom, 0);
				} else {
					TypedVal id0 = eval(e.elems.get(2));
					if (id0.domId != 0xFFFF && id0.domId != dom)
						throw new IllegalArgumentException("DOMAIN_MISMATCH: seed id domain mismatch");
					b.emitU16(ImpulseIsaTables.OP_FSEED, ImpulseIsaTables.SUBOP_FSEED_SINGLE_REG, 0, dst, id0.reg, 0, 0,
							dom, 0);
					for (int i = 3; i < e.elems.size(); i++) {
						TypedVal idi = eval(e.elems.get(i));
						b.emitU16(ImpulseIsaTables.OP_FSEED, ImpulseIsaTables.SUBOP_FSEED_INSERT_REG, 0, dst, idi.reg,
								0, 0, dom, 0);
					}
				}
				return TypedVal.frontier(dst, dom);
			}
			if ("walk".equals(head)) {
				String dir = e.elems.get(1).text;
				String relName = e.elems.get(2).text;
				RelInfo rel = resolveRel(relName);
				TypedVal srcF = eval(e.elems.get(3));
				if (srcF.bank != ValBank.FRONTIER)
					throw new IllegalArgumentException("TYPE_MISMATCH: walk expects frontier");

				boolean transPlus = false, transStar = false, hasExcl = false;
				TypedVal exclF = null;
				for (int i = 4; i < e.elems.size(); i++) {
					if (e.elems.get(i).kind == Kind.KEYWORD) {
						String kw = e.elems.get(i).text;
						if (":transitive+".equals(kw))
							transPlus = true;
						else if (":transitive*".equals(kw))
							transStar = true;
						else if (":exclude".equals(kw) && i + 1 < e.elems.size()) {
							exclF = eval(e.elems.get(++i));
							hasExcl = true;
						}
					}
				}
				int expectedSrc = ":rev".equals(dir) ? rel.tgtDom : rel.srcDom;
				int outDom = ":rev".equals(dir) ? rel.srcDom : rel.tgtDom;
				if ((":both".equals(dir) || transPlus || transStar) && rel.srcDom != rel.tgtDom) {
					throw new IllegalArgumentException("DOMAIN_MISMATCH: non-endo relation '" + relName + "'");
				}
				if (srcF.domId != expectedSrc) {
					throw new IllegalArgumentException(
							"DOMAIN_MISMATCH: walk source frontier domain mismatch on '" + relName + "'");
				}
				if (hasExcl && exclF.domId != outDom) {
					throw new IllegalArgumentException("DOMAIN_MISMATCH: walk :exclude frontier domain mismatch");
				}
				int wsub = ImpulseIsaTables.SUBOP_WALK_FWD;
				if (":fwd".equals(dir)) {
					wsub = transStar
							? ImpulseIsaTables.SUBOP_WALK_FWD_FIXPOINT_STAR
							: (transPlus
									? ImpulseIsaTables.SUBOP_WALK_FWD_FIXPOINT_PLUS
									: ImpulseIsaTables.SUBOP_WALK_FWD);
				} else if (":rev".equals(dir)) {
					wsub = transStar
							? ImpulseIsaTables.SUBOP_WALK_REV_FIXPOINT_STAR
							: (transPlus
									? ImpulseIsaTables.SUBOP_WALK_REV_FIXPOINT_PLUS
									: ImpulseIsaTables.SUBOP_WALK_REV);
				} else if (":both".equals(dir)) {
					wsub = ImpulseIsaTables.SUBOP_WALK_BOTH;
				}
				int dst = allocF();
				int flags = hasExcl ? ImpulseIsaTables.FLAG_MASKED_S3 : 0;
				int s3 = hasExcl ? exclF.reg : 0;
				b.emitU16(ImpulseIsaTables.OP_WALK, wsub, flags, dst, srcF.reg, 0, s3, rel.relId, 0);
				return TypedVal.frontier(dst, outDom);
			}
			if ("set-union".equals(head) || "set-intersect".equals(head) || "set-diff".equals(head)
					|| "set-sym-diff".equals(head)) {
				TypedVal a = eval(e.elems.get(1));
				TypedVal bv = eval(e.elems.get(2));
				if (a.bank != ValBank.FRONTIER || bv.bank != ValBank.FRONTIER)
					throw new IllegalArgumentException("TYPE_MISMATCH: set-op requires frontiers");
				if (a.domId != bv.domId)
					throw new IllegalArgumentException("DOMAIN_MISMATCH: set-op domain mismatch");
				int ssub = switch (head) {
					case "set-intersect" -> ImpulseIsaTables.SUBOP_SET_OP_INTERSECT;
					case "set-diff" -> ImpulseIsaTables.SUBOP_SET_OP_DIFF;
					case "set-sym-diff" -> ImpulseIsaTables.SUBOP_SET_OP_SYM_DIFF;
					default -> ImpulseIsaTables.SUBOP_SET_OP_UNION;
				};
				int dst = allocF();
				b.emit(ImpulseIsaTables.OP_SET_OP, ssub, 0, dst, a.reg, bv.reg, 0, 0);
				return TypedVal.frontier(dst, a.domId);
			}
			if ("cardinality".equals(head)) {
				TypedVal f = eval(e.elems.get(1));
				if (f.bank != ValBank.FRONTIER)
					throw new IllegalArgumentException("TYPE_MISMATCH: cardinality requires frontier");
				int dst = allocR();
				b.emit(ImpulseIsaTables.OP_SET_CARDINALITY, ImpulseIsaTables.SUBOP_SET_CARDINALITY_CARD_1BIT, 0, dst,
						f.reg, 0, 0, 0);
				return TypedVal.scalar(ValBank.SCALAR_U64, dst);
			}
			if ("contains?".equals(head)) {
				TypedVal f = eval(e.elems.get(1));
				TypedVal id = eval(e.elems.get(2));
				if (f.bank != ValBank.FRONTIER)
					throw new IllegalArgumentException("TYPE_MISMATCH: contains? requires frontier");
				if (id.domId != 0xFFFF && id.domId != f.domId)
					throw new IllegalArgumentException("DOMAIN_MISMATCH: contains? id domain mismatch");
				int dst = allocR();
				b.emit(ImpulseIsaTables.OP_SET_CONTAINS, ImpulseIsaTables.SUBOP_SET_CONTAINS_CONTAINS_1BIT, 0, dst,
						f.reg, id.reg, 0, 0);
				return TypedVal.scalar(ValBank.SCALAR_BOOL, dst);
			}

			// Columnar attributes & vectors
			if ("load-node-attr".equals(head)) {
				int dom = resolveDomain(e.elems.get(1).text);
				String attrName = e.elems.get(2).text;
				AttrInfo ai = resolveNodeAttr(dom, attrName);
				int dstV = allocV();
				b.emitU16(ImpulseIsaTables.OP_LOAD_COLUMN, ImpulseIsaTables.SUBOP_LOAD_COLUMN_NODE_ATTR, 0, dstV, 0, 0,
						0, dom, ai.attrId);
				if (ai.isDecimal()) {
					return TypedVal.vecDecimal(dstV, dom, ai.precision, ai.scale);
				}
				return TypedVal.vec(attrTypeToVecBank(ai), dstV, dom);
			}
			if ("vec-fill".equals(head)) {
				// (vec-fill "Dom" type val)
				int dom = resolveDomain(e.elems.get(1).text);
				TypeSpec ts = parseScalarTypeSpec(e.elems.get(2));
				TypedVal initVal = eval(e.elems.get(3));
				int dstV = allocV();
				int sub = switch (ts.scalarBank) {
					case SCALAR_I64, SCALAR_DECIMAL -> ImpulseIsaTables.SUBOP_VEC_INIT_DOM_I64;
					case SCALAR_U64 -> ImpulseIsaTables.SUBOP_VEC_INIT_DOM_U64;
					case SCALAR_F64 -> ImpulseIsaTables.SUBOP_VEC_INIT_DOM_F64;
					case SCALAR_BOOL -> ImpulseIsaTables.SUBOP_VEC_INIT_DOM_BOOL_3VL;
					default -> throw new IllegalArgumentException("TYPE_MISMATCH: unsupported vec-fill type");
				};
				if (ts.scalarBank == ValBank.SCALAR_DECIMAL) {
					TypedVal dv = ensureDecimal(initVal, "vec-fill");
					if (dv.isConst && dv.constVal == 0L) {
						b.emitU16(ImpulseIsaTables.OP_VEC_INIT, sub, 0, dstV, 0, 0, 0, dom, 0);
					} else {
						TypedVal castVal = evalCast(dv, ts);
						b.emitU16(ImpulseIsaTables.OP_VEC_INIT, sub, ImpulseIsaTables.FLAG_SCALAR_RHS, dstV, 0,
								castVal.reg, 0, dom, 0);
					}
					return TypedVal.vecDecimal(dstV, dom, ts.precision, ts.scale);
				}
				b.emitU16(ImpulseIsaTables.OP_VEC_INIT, sub, ImpulseIsaTables.FLAG_SCALAR_RHS, dstV, 0, initVal.reg, 0,
						dom, 0);
				ValBank vb = switch (ts.scalarBank) {
					case SCALAR_U64 -> ValBank.VEC_U64;
					case SCALAR_F64 -> ValBank.VEC_F64;
					case SCALAR_BOOL -> ValBank.VEC_BOOL;
					default -> ValBank.VEC_I64;
				};
				return TypedVal.vec(vb, dstV, dom);
			}
			if ("node-filter".equals(head)) {
				return evalNodeFilter(e);
			}
			if ("index-scan".equals(head)) {
				return evalIndexScan(e);
			}
			if ("edge-stream".equals(head)) {
				return evalEdgeStream(e);
			}
			if ("edge-attr".equals(head)) {
				if (morsel == null) {
					throw new IllegalArgumentException("PARSE_ERROR: edge-attr only valid inside edge-stream");
				}
				String attrName = e.elems.get(2).text;
				AttrInfo ai = resolveEdgeAttr(morsel.rel.name, attrName);
				int dstV = allocV();
				b.emitU16(ImpulseIsaTables.OP_GATHER_EDGE_ATTR, ImpulseIsaTables.SUBOP_GATHER_EDGE_ATTR_VEC, 0, dstV,
						10, 0, 0, morsel.rel.relId, ai.attrId);
				if (ai.isDecimal()) {
					return TypedVal.vecDecimal(dstV, morsel.rel.tgtDom, ai.precision, ai.scale);
				}
				return TypedVal.vec(attrTypeToVecBank(ai), dstV, morsel.rel.tgtDom);
			}
			if ("attr".equals(head)) {
				String cursor = e.elems.get(1).text;
				String attrName = e.elems.get(2).text;
				if (morsel == null && nodeFilterVar != null && cursor.equals(nodeFilterVar)) {
					AttrInfo ai = resolveNodeAttr(nodeFilterDom, attrName);
					int dstV = allocV();
					b.emitU16(ImpulseIsaTables.OP_LOAD_COLUMN, ImpulseIsaTables.SUBOP_LOAD_COLUMN_NODE_ATTR, 0, dstV, 0,
							0, 0, nodeFilterDom, ai.attrId);
					if (ai.isDecimal()) {
						return TypedVal.vecDecimal(dstV, nodeFilterDom, ai.precision, ai.scale);
					}
					return TypedVal.vec(attrTypeToVecBank(ai), dstV, nodeFilterDom);
				}
				if (morsel == null) {
					throw new IllegalArgumentException("PARSE_ERROR: attr only valid inside edge-stream/node-filter");
				}
				int idxV;
				int domId;
				if (cursor.equals(morsel.srcVar)) {
					idxV = 8;
					domId = morsel.rel.srcDom;
				} else if (cursor.equals(morsel.tgtVar)) {
					idxV = 9;
					domId = morsel.rel.tgtDom;
				} else {
					throw new IllegalArgumentException("UNBOUND_SYMBOL: unknown morsel cursor '" + cursor + "'");
				}
				AttrInfo ai = resolveNodeAttr(domId, attrName);
				int dstV = allocV();
				b.emitU16(ImpulseIsaTables.OP_GATHER_NODE_ATTR, ImpulseIsaTables.SUBOP_GATHER_NODE_ATTR_VEC, 0, dstV,
						idxV, 0, 0, domId, ai.attrId);
				if (ai.isDecimal()) {
					return TypedVal.vecDecimal(dstV, domId, ai.precision, ai.scale);
				}
				return TypedVal.vec(attrTypeToVecBank(ai), dstV, domId);
			}
			if ("and".equals(head) || "or".equals(head)) {
				TypedVal lhs = eval(e.elems.get(1));
				int savedPinV = pinV(lhs);
				TypedVal rhs;
				try {
					rhs = eval(e.elems.get(2));
				} finally {
					pinnedVMask = savedPinV;
				}
				if (lhs.bank == ValBank.VEC_BOOL && rhs.bank == ValBank.VEC_BOOL) {
					int dst = allocDstV(lhs, rhs);
					int sub = "and".equals(head)
							? ImpulseIsaTables.SUBOP_VEC_3VL_OP_AND
							: ImpulseIsaTables.SUBOP_VEC_3VL_OP_OR;
					b.emit(ImpulseIsaTables.OP_VEC_3VL_OP, sub, 0, dst, lhs.reg, rhs.reg, 0, 0);
					return TypedVal.vec(ValBank.VEC_BOOL, dst, lhs.domId);
				}
				throw new IllegalArgumentException(
						"TYPE_MISMATCH: " + head + " expects BOOL vectors, got " + lhs.bank + " and " + rhs.bank);
			}
			if ("not".equals(head)) {
				TypedVal v = eval(e.elems.get(1));
				if (v.bank == ValBank.VEC_BOOL) {
					int dst = allocDstV(v, null);
					b.emit(ImpulseIsaTables.OP_VEC_3VL_OP, ImpulseIsaTables.SUBOP_VEC_3VL_OP_NOT, 0, dst, v.reg, 0, 0,
							0);
					return TypedVal.vec(ValBank.VEC_BOOL, dst, v.domId);
				}
				throw new IllegalArgumentException("TYPE_MISMATCH: not expects BOOL vector, got " + v.bank);
			}
			if ("vec-at".equals(head)) {
				if (morsel == null) {
					throw new IllegalArgumentException("PARSE_ERROR: vec-at only valid inside edge-stream");
				}
				TypedVal vec = eval(e.elems.get(1));
				String cursor = e.elems.get(2).text;
				int idxV = cursor.equals(morsel.srcVar) ? 8 : (cursor.equals(morsel.tgtVar) ? 9 : -1);
				if (idxV < 0) {
					throw new IllegalArgumentException("UNBOUND_SYMBOL: unknown morsel cursor '" + cursor + "'");
				}
				int dstV = allocV();
				b.emit(ImpulseIsaTables.OP_GATHER_VEC, ImpulseIsaTables.SUBOP_GATHER_VEC_GATHER, 0, dstV, vec.reg, idxV,
						0, 0);
				return vec.bank.isDecimal()
						? TypedVal.vecDecimal(dstV, vec.domId, vec.precision, vec.scale)
						: TypedVal.vec(vec.bank, dstV, vec.domId);
			}

			// Reductions: (reduce :sum|:min|:max|:avg|:count-valid v [:mask f])
			if ("reduce".equals(head)) {
				String kind = e.elems.get(1).text;
				TypedVal v = eval(e.elems.get(2));
				if (!v.bank.isVec()) {
					throw new IllegalArgumentException("TYPE_MISMATCH: reduce expects vector");
				}
				int flags = 0;
				int maskF = 0;
				if (e.elems.size() >= 5 && ":mask".equals(e.elems.get(3).text)) {
					TypedVal mf = eval(e.elems.get(4));
					if (mf.bank != ValBank.FRONTIER || mf.domId != v.domId) {
						throw new IllegalArgumentException("DOMAIN_MISMATCH: reduce :mask frontier mismatch");
					}
					flags = ImpulseIsaTables.FLAG_MASKED_S3;
					maskF = mf.reg;
				}
				if (v.bank == ValBank.VEC_DECIMAL) {
					if (":sum".equals(kind)) {
						int dst = allocR();
						b.emitVecReduceSumI64Chk(dst, v.reg, flags, maskF);
						return TypedVal.decimal(dst, 18, v.scale);
					}
					if (":min".equals(kind) || ":max".equals(kind)) {
						// Exact signed 64-bit integer min/max loop over active valid lanes (zero f64
						// precision loss above 2^53, 100% 4-way C++/Java/OCaml parity).
						boolean isMin = ":min".equals(kind);
						int savedPinR = pinnedRMask;
						int savedPinV = pinV(v);
						try {
							int rAcc = allocPinnedR();
							int rIdx = allocPinnedR();
							int rLen = allocPinnedR();
							int rOne = allocPinnedR();
							int rCur = allocPinnedR();
							int rTmp = allocPinnedR();

							b.emit(ImpulseIsaTables.OP_LOAD_CONST, ImpulseIsaTables.SUBOP_LOAD_CONST_NULL, 0, rAcc, 0,
									0, 0, 0);
							b.emitLoadConstI64(rIdx, 0L);
							b.emitLoadConstI64(rOne, 1L);
							b.emit(ImpulseIsaTables.OP_VEC_POINT, ImpulseIsaTables.SUBOP_VEC_POINT_VEC_LEN, 0, rLen,
									v.reg, 0, 0, 0);

							int loopPc = b.pc();
							b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_CMP_LT_I64, 0,
									rTmp, rIdx, rLen, 0, 0);
							int jccEndPc = b.emit(ImpulseIsaTables.OP_JCC, 7, 0, 0, rTmp, 0, 0, 0);

							int jccSkipMaskPc = -1;
							if ((flags & ImpulseIsaTables.FLAG_MASKED_S3) != 0) {
								b.emit(ImpulseIsaTables.OP_SET_CONTAINS,
										ImpulseIsaTables.SUBOP_SET_CONTAINS_CONTAINS_1BIT, 0, rTmp, maskF, rIdx, 0, 0);
								jccSkipMaskPc = b.emit(ImpulseIsaTables.OP_JCC, 7, 0, 0, rTmp, 0, 0, 0);
							}

							b.emit(ImpulseIsaTables.OP_VEC_POINT, ImpulseIsaTables.SUBOP_VEC_POINT_VEC_GET, 0, rCur,
									v.reg, rIdx, 0, 0);
							b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_IS_NULL, 0, rTmp,
									rCur, 0, 0, 0);
							int jccSkipNullPc = b.emit(ImpulseIsaTables.OP_JCC, 6, 0, 0, rTmp, 0, 0, 0);

							b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_IS_NULL, 0, rTmp,
									rAcc, 0, 0, 0);
							int jccTakeFirstPc = b.emit(ImpulseIsaTables.OP_JCC, 6, 0, 0, rTmp, 0, 0, 0);

							if (isMin) {
								b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_CMP_LT_I64, 0,
										rTmp, rCur, rAcc, 0, 0);
							} else {
								b.emit(ImpulseIsaTables.OP_SCALAR_3VL, ImpulseIsaTables.SUBOP_SCALAR_3VL_CMP_LT_I64, 0,
										rTmp, rAcc, rCur, 0, 0);
							}
							int jccNotBetterPc = b.emit(ImpulseIsaTables.OP_JCC, 7, 0, 0, rTmp, 0, 0, 0);

							int takePc = b.pc();
							b.patchImm32(jccTakeFirstPc, takePc - jccTakeFirstPc);
							b.emit(ImpulseIsaTables.OP_MOV, ImpulseIsaTables.SUBOP_MOV_R, 0, rAcc, rCur, 0, 0, 0);

							int nextPc = b.pc();
							if (jccSkipMaskPc >= 0) {
								b.patchImm32(jccSkipMaskPc, nextPc - jccSkipMaskPc);
							}
							b.patchImm32(jccSkipNullPc, nextPc - jccSkipNullPc);
							b.patchImm32(jccNotBetterPc, nextPc - jccNotBetterPc);
							b.emit(ImpulseIsaTables.OP_SCALAR_INT_ALU, ImpulseIsaTables.SUBOP_SCALAR_INT_ALU_ADD, 0,
									rIdx, rIdx, rOne, 0, 0);
							int jmpLoopPc = b.emit(ImpulseIsaTables.OP_JMP, 0, 0, 0, 0, 0, 0, 0);
							b.patchImm32(jmpLoopPc, loopPc - jmpLoopPc);

							int endPc = b.pc();
							b.patchImm32(jccEndPc, endPc - jccEndPc);
							return TypedVal.decimal(rAcc, v.precision, v.scale);
						} finally {
							pinnedRMask = savedPinR;
							pinnedVMask = savedPinV;
						}
					}
					if (":count-valid".equals(kind)) {
						int dst = allocR();
						b.emit(ImpulseIsaTables.OP_VEC_REDUCE, ImpulseIsaTables.SUBOP_VEC_REDUCE_COUNT_VALID, flags,
								dst, v.reg, 0, maskF, 0);
						return TypedVal.scalar(ValBank.SCALAR_U64, dst);
					}
					if (":avg".equals(kind) || ":mean".equals(kind)) {
						int rSum = allocR();
						int savedPin = pinnedRMask;
						pinnedRMask |= (1 << rSum);
						int rCnt = allocR();
						pinnedRMask |= (1 << rCnt);
						b.emitVecReduceSumI64Chk(rSum, v.reg, flags, maskF);
						b.emit(ImpulseIsaTables.OP_VEC_REDUCE, ImpulseIsaTables.SUBOP_VEC_REDUCE_COUNT_VALID, flags,
								rCnt, v.reg, 0, maskF, 0);
						int dst = allocR();
						pinnedRMask = savedPin;
						b.emitScalarDecDivSafe(dst, rSum, rCnt);
						return TypedVal.decimal(dst, 18, v.scale);
					}
					throw new IllegalArgumentException("TYPE_MISMATCH: unsupported decimal reduction '" + kind + "'");
				}
				int rsub = switch (kind) {
					case ":sum" -> ImpulseIsaTables.SUBOP_VEC_REDUCE_SUM;
					case ":min" -> ImpulseIsaTables.SUBOP_VEC_REDUCE_MIN;
					case ":max" -> ImpulseIsaTables.SUBOP_VEC_REDUCE_MAX;
					case ":count-valid" -> ImpulseIsaTables.SUBOP_VEC_REDUCE_COUNT_VALID;
					case ":mean", ":avg" -> ImpulseIsaTables.SUBOP_VEC_REDUCE_MEAN;
					default -> throw new IllegalArgumentException("PARSE_ERROR: unknown reduce kind '" + kind + "'");
				};
				int dst = allocR();
				b.emit(ImpulseIsaTables.OP_VEC_REDUCE, rsub, flags, dst, v.reg, 0, maskF, 0);
				ValBank outB = ":count-valid".equals(kind) ? ValBank.SCALAR_U64 : ValBank.SCALAR_F64;
				return TypedVal.scalar(outB, dst);
			}

			// Unary math & decimal ops
			if ("abs".equals(head) || "neg".equals(head)) {
				TypedVal v = eval(e.elems.get(1));
				if (v.bank.isDecimal()) {
					if (v.bank.isScalar()) {
						int dst = allocR();
						if ("abs".equals(head)) {
							b.emitScalarDecAbsChk(dst, v.reg);
						} else {
							b.emitScalarDecNegChk(dst, v.reg);
						}
						return TypedVal.decimal(dst, v.precision, v.scale);
					}
					int dst = allocV();
					if ("abs".equals(head)) {
						b.emitVecDecAbsChk(dst, v.reg, 0, 0);
					} else {
						b.emitVecDecNegChk(dst, v.reg, 0, 0);
					}
					return TypedVal.vecDecimal(dst, v.domId, v.precision, v.scale);
				}
				if (v.bank == ValBank.SCALAR_F64) {
					int dst = allocR();
					b.emit(ImpulseIsaTables.OP_SCALAR_FP_ALU,
							"abs".equals(head)
									? ImpulseIsaTables.SUBOP_SCALAR_FP_ALU_ABS
									: ImpulseIsaTables.SUBOP_SCALAR_FP_ALU_NEG,
							0, dst, v.reg, 0, 0, 0);
					return TypedVal.scalar(ValBank.SCALAR_F64, dst);
				}
				if (v.bank == ValBank.VEC_F64) {
					int dst = allocV();
					b.emit(ImpulseIsaTables.OP_VEC_MATH_ARITH,
							"abs".equals(head)
									? ImpulseIsaTables.SUBOP_VEC_MATH_ARITH_ABS
									: ImpulseIsaTables.SUBOP_VEC_MATH_ARITH_NEG,
							0, dst, v.reg, 0, 0, 0);
					return TypedVal.vec(ValBank.VEC_F64, dst, v.domId);
				}
				throw new IllegalArgumentException("TYPE_MISMATCH: " + head + " unsupported on " + v.bank);
			}
			if ("log".equals(head) || "exp".equals(head) || "sqrt".equals(head)) {
				TypedVal v = eval(e.elems.get(1));
				if (v.bank.isDecimal()) {
					throw new IllegalArgumentException(
							"TYPE_MISMATCH: transcendental math '" + head + "' forbids DECIMAL; use (cast expr f64)");
				}
				if (v.bank == ValBank.VEC_F64) {
					int dst = allocV();
					if ("sqrt".equals(head)) {
						b.emit(ImpulseIsaTables.OP_VEC_MATH_ARITH, ImpulseIsaTables.SUBOP_VEC_MATH_ARITH_SQRT, 0, dst,
								v.reg, 0, 0, 0);
					} else {
						int esub = "log".equals(head)
								? ImpulseIsaTables.SUBOP_VEC_MATH_EXP_LOG_LOG
								: ImpulseIsaTables.SUBOP_VEC_MATH_EXP_LOG_EXP;
						b.emit(ImpulseIsaTables.OP_VEC_MATH_EXP_LOG, esub, 0, dst, v.reg, 0, 0, 0);
					}
					return TypedVal.vec(ValBank.VEC_F64, dst, v.domId);
				}
				if (v.bank == ValBank.SCALAR_F64 && "sqrt".equals(head)) {
					int dst = allocR();
					b.emit(ImpulseIsaTables.OP_SCALAR_FP_ALU, ImpulseIsaTables.SUBOP_SCALAR_FP_ALU_SQRT, 0, dst, v.reg,
							0, 0, 0);
					return TypedVal.scalar(ValBank.SCALAR_F64, dst);
				}
				throw new IllegalArgumentException("TYPE_MISMATCH: " + head + " requires f64 vector, got " + v.bank);
			}

			// Binary arithmetic (+, -, *, /, safe-div)
			if ("+".equals(head) || "-".equals(head) || "*".equals(head) || "/".equals(head)
					|| "safe-div".equals(head)) {
				TypedVal lhs = eval(e.elems.get(1));
				int savedPinR = pinR(lhs);
				int savedPinV = pinV(lhs);
				TypedVal rhs;
				try {
					rhs = eval(e.elems.get(2));
				} finally {
					pinnedRMask = savedPinR;
					pinnedVMask = savedPinV;
				}
				if (lhs.bank.isDecimal() || rhs.bank.isDecimal()) {
					return evalDecimalBinary(head, lhs, rhs);
				}
				if (lhs.bank.isStr() || rhs.bank.isStr() || lhs.bank.isBool() || rhs.bank.isBool()) {
					throw new IllegalArgumentException("TYPE_MISMATCH: arithmetic operator '" + head + "' invalid on "
							+ lhs.bank + " and " + rhs.bank);
				}
				boolean li = lhs.bank.isInt();
				boolean ri = rhs.bank.isInt();
				boolean lf = lhs.bank.isFloat();
				boolean rf = rhs.bank.isFloat();
				if ((li && rf) || (lf && ri)) {
					throw new IllegalArgumentException(
							"TYPE_MISMATCH: implicit int/float mixing forbidden; use (cast ...)");
				}
				if (!lhs.bank.isVec() && !rhs.bank.isVec()) {
					int dst = allocR();
					if (li && ri) {
						int asub = switch (head) {
							case "-" -> ImpulseIsaTables.SUBOP_SCALAR_INT_ALU_SUB;
							case "*" -> ImpulseIsaTables.SUBOP_SCALAR_INT_ALU_MUL;
							case "/", "safe-div" -> ImpulseIsaTables.SUBOP_SCALAR_INT_ALU_DIV_S;
							default -> ImpulseIsaTables.SUBOP_SCALAR_INT_ALU_ADD;
						};
						b.emit(ImpulseIsaTables.OP_SCALAR_INT_ALU, asub, 0, dst, lhs.reg, rhs.reg, 0, 0);
						return TypedVal.scalar(ValBank.SCALAR_I64, dst);
					}
					int fsub = switch (head) {
						case "-" -> ImpulseIsaTables.SUBOP_SCALAR_FP_ALU_SUB;
						case "*" -> ImpulseIsaTables.SUBOP_SCALAR_FP_ALU_MUL;
						case "/", "safe-div" -> ImpulseIsaTables.SUBOP_SCALAR_FP_ALU_DIV;
						default -> ImpulseIsaTables.SUBOP_SCALAR_FP_ALU_ADD;
					};
					b.emit(ImpulseIsaTables.OP_SCALAR_FP_ALU, fsub, 0, dst, lhs.reg, rhs.reg, 0, 0);
					return TypedVal.scalar(ValBank.SCALAR_F64, dst);
				}
				int outDom = lhs.bank.isVec() ? lhs.domId : rhs.domId;
				int msub = switch (head) {
					case "-" -> ImpulseIsaTables.SUBOP_VEC_MATH_ARITH_SUB;
					case "*" -> ImpulseIsaTables.SUBOP_VEC_MATH_ARITH_MUL;
					case "/", "safe-div" -> ImpulseIsaTables.SUBOP_VEC_MATH_ARITH_DIV;
					default -> ImpulseIsaTables.SUBOP_VEC_MATH_ARITH_ADD;
				};
				int flags = (!rhs.bank.isVec()) ? ImpulseIsaTables.FLAG_SCALAR_RHS : 0;
				int dst = allocV();
				b.emit(ImpulseIsaTables.OP_VEC_MATH_ARITH, msub, flags, dst, lhs.reg, rhs.reg, 0, 0);
				return TypedVal.vec(lf ? ValBank.VEC_F64 : ValBank.VEC_I64, dst, outDom);
			}

			// Comparisons (=, !=, <, <=, >, >=)
			if ("=".equals(head) || "!=".equals(head) || "<".equals(head) || "<=".equals(head) || ">".equals(head)
					|| ">=".equals(head)) {
				TypedVal lhs = eval(e.elems.get(1));
				int savedPinR = pinR(lhs);
				int savedPinV = pinV(lhs);
				TypedVal rhs;
				try {
					rhs = eval(e.elems.get(2));
				} finally {
					pinnedRMask = savedPinR;
					pinnedVMask = savedPinV;
				}
				return evalComparison(head, lhs, rhs);
			}

			if ("cast".equals(head)) {
				TypedVal v = eval(e.elems.get(1));
				TypeSpec target = parseScalarTypeSpec(e.elems.get(2));
				return evalCast(v, target);
			}

			if ("collect".equals(head)) {
				String kind = e.elems.get(1).text;
				String cname = e.elems.get(2).text;
				TypedVal v = eval(e.elems.get(3));
				int slot = outputSlot++;
				outputNames.add(cname);
				outputSchema.add(v.toOutputMeta(cname));
				int flags = 0;
				int maskF = 0;
				if (e.elems.size() >= 6 && ":mask".equals(e.elems.get(4).text)) {
					TypedVal mf = eval(e.elems.get(5));
					flags = ImpulseIsaTables.FLAG_MASKED_S3;
					maskF = mf.reg;
				}
				int csub = switch (kind) {
					case ":frontier-bitset" -> ImpulseIsaTables.SUBOP_COLLECT_FRONTIER_BITSET;
					case ":frontier-ids" -> ImpulseIsaTables.SUBOP_COLLECT_FRONTIER_IDS;
					case ":vec" -> ImpulseIsaTables.SUBOP_COLLECT_VEC;
					case ":scalar" -> ImpulseIsaTables.SUBOP_COLLECT_SCALAR;
					default -> throw new IllegalArgumentException("PARSE_ERROR: unknown collect kind '" + kind + "'");
				};
				b.emitU16(ImpulseIsaTables.OP_COLLECT, csub, flags, 0, v.reg, 0, maskF, slot, 0);
				return v;
			}
			throw new IllegalArgumentException("PARSE_ERROR: unsupported ImpScheme v0.11 form '" + head + "'");
		}
	}

	public static CompileResult compile(String source, ImpulseGraphSnapshot snapshot, boolean enableOptimizations) {
		SExpr root = new Parser(source).parseOne();
		if (root.kind != Kind.LIST || root.elems.isEmpty() || !"query".equals(root.elems.get(0).text)) {
			throw new IllegalArgumentException("PARSE_ERROR: expected (query ...) root");
		}
		Ctx ctx = new Ctx(snapshot);
		for (int i = 1; i < root.elems.size(); i++) {
			SExpr form = root.elems.get(i);
			if (form.kind == Kind.LIST && !form.elems.isEmpty() && "param".equals(form.elems.get(0).text)) {
				String pname = form.elems.get(1).text;
				int pid = ctx.nextParamId++;
				ctx.paramMap.put(pname, pid);
				SExpr ty = form.elems.get(2);
				if (ty.kind == Kind.LIST && ty.elems.size() == 2 && "id".equals(ty.elems.get(0).text)) {
					int dom = ctx.resolveDomain(ty.elems.get(1).text);
					int r = ctx.allocR();
					ctx.b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_SCALAR, 0, r, 0, 0,
							0, pid, ImpulseIsaTables.SCALAR_U64);
					ctx.env.put(pname, TypedVal.scalarWithDom(ValBank.SCALAR_U64, r, dom));
				} else if (ty.kind == Kind.LIST && ty.elems.size() == 2 && "frontier".equals(ty.elems.get(0).text)) {
					int dom = ctx.resolveDomain(ty.elems.get(1).text);
					int f = ctx.allocF();
					ctx.b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, f, 0,
							0, 0, pid, dom);
					ctx.env.put(pname, TypedVal.frontier(f, dom));
				} else if (ty.kind == Kind.LIST && ty.elems.size() == 3 && "decimal".equals(ty.elems.get(0).text)) {
					TypeSpec ts = ctx.parseScalarTypeSpec(ty);
					int r = ctx.allocR();
					ctx.b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_SCALAR, 0, r, 0, 0,
							0, pid, ImpulseIsaTables.SCALAR_I64);
					ctx.env.put(pname, TypedVal.decimal(r, ts.precision, ts.scale));
				} else if (ty.kind == Kind.SYMBOL) {
					int r = ctx.allocR();
					ValBank vb = switch (ty.text) {
						case "u64", "u32" -> ValBank.SCALAR_U64;
						case "f64", "f32" -> ValBank.SCALAR_F64;
						case "bool" -> ValBank.SCALAR_BOOL;
						case "str" -> ValBank.SCALAR_STR;
						default -> ValBank.SCALAR_I64;
					};
					int stag = switch (vb) {
						case SCALAR_U64 -> ImpulseIsaTables.SCALAR_U64;
						case SCALAR_F64 -> ImpulseIsaTables.SCALAR_F64;
						case SCALAR_BOOL -> ImpulseIsaTables.SCALAR_BOOL_3VL;
						case SCALAR_STR -> ImpulseIsaTables.SCALAR_STR_HANDLE;
						default -> ImpulseIsaTables.SCALAR_I64;
					};
					ctx.b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_SCALAR, 0, r, 0, 0,
							0, pid, stag);
					ctx.env.put(pname, TypedVal.scalar(vb, r));
				} else {
					throw new IllegalArgumentException("PARSE_ERROR: unsupported param type");
				}
			} else {
				ctx.eval(form);
			}
		}
		if (!enableOptimizations) {
			ctx.b.emit(ImpulseIsaTables.OP_NOP, 0, 0, 0, 0, 0, 0, 0);
		}
		ctx.b.emit(ImpulseIsaTables.OP_HALT, ImpulseIsaTables.SUBOP_HALT_OK, 0, 0, 0, 0, 0, 0);

		int reqDoms = (snapshot != null && snapshot.getDomainNames() != null) ? snapshot.getDomainNames().size() : 0;
		ImpulseImpbContainer container = ctx.b.buildImpb(0, 0, reqDoms);
		ImpulseValidator.ValidationResult vr = ImpulseValidator.validate(container.codeWords);
		if (vr.status() != ImpulseIsaTables.IMPULSE_VM_OK) {
			throw new IllegalStateException("VALIDATION_ERROR: invalid v0.11 bytecode at pc=" + vr.badPc());
		}
		return new CompileResult(container, ctx.paramMap, ctx.outputNames, List.copyOf(ctx.outputSchema));
	}
}
