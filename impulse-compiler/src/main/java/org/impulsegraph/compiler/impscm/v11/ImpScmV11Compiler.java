package org.impulsegraph.compiler.impscm.v11;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.compiler.emitter.V11ProgramBuilder;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.vm.v11.V11ImpbContainer;
import org.impulsegraph.vm.v11.V11IsaTables;
import org.impulsegraph.vm.v11.V11Validator;

import java.util.*;

/**
 * ImpScheme v0.11.0 SSA IR ({@code (query ...)}) Parser, Static Domain/Type
 * Validator ({@code IMPSCM_SPECIFICATION_v0_11.md} §7.1), and {@code .impb}
 * v0.11 Compiler. Mirrors C++ {@code impulse::compiler::v11::ImpScmV11Compiler}
 * bit-for-bit.
 */
public final class ImpScmV11Compiler {

	private ImpScmV11Compiler() {
	}

	public record CompileResult(V11ImpbContainer container, Map<String, Integer> paramNameToId,
			List<String> outputNames) {
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
		SCALAR_I64, SCALAR_U64, SCALAR_F64, SCALAR_BOOL, FRONTIER, VEC
	}
	private record TypedVal(ValBank bank, int reg, int domId) {
	}
	private record RelInfo(int relId, int srcDom, int tgtDom) {
	}

	private static final class Ctx {
		final ImpulseGraphSnapshot snap;
		final V11ProgramBuilder b = new V11ProgramBuilder();
		int nextR = 0, nextF = 0, nextParamId = 0, outputSlot = 0;
		Map<String, TypedVal> env = new HashMap<>();
		final Map<String, Integer> paramMap = new LinkedHashMap<>();
		final List<String> outputNames = new ArrayList<>();

		Ctx(ImpulseGraphSnapshot snap) {
			this.snap = snap;
		}

		int allocR() {
			if (nextR >= 16)
				throw new IllegalStateException("REGISTER_EXHAUSTION: R0..R15");
			return nextR++;
		}
		int allocF() {
			if (nextF >= 16)
				throw new IllegalStateException("REGISTER_EXHAUSTION: F0..F15");
			return nextF++;
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

		RelInfo resolveRel(String name) {
			if (snap == null)
				return new RelInfo(0, 0, 0);
			RelationSnapshot rs = snap.getRelationSnapshot(name);
			if (rs == null) {
				throw new IllegalArgumentException("UNKNOWN_SCHEMA_ENTITY: unknown relation '" + name + "'");
			}
			int rid = rs.getRelationId() >= 0 ? rs.getRelationId() : 0;
			return new RelInfo(rid, rs.getSrcDomainId(), rs.getTgtDomainId());
		}

		TypedVal eval(SExpr e) {
			if (e.kind == Kind.INT) {
				int r = allocR();
				b.emitLoadConstI64(r, e.iVal);
				return new TypedVal(ValBank.SCALAR_I64, r, 0xFFFF);
			}
			if (e.kind == Kind.FLOAT) {
				int r = allocR();
				b.emitLoadConstF64(r, e.fVal);
				return new TypedVal(ValBank.SCALAR_F64, r, 0xFFFF);
			}
			if (e.kind == Kind.BOOL) {
				int r = allocR();
				b.emitLoadConstI64(r, e.bVal ? 1L : 0L);
				return new TypedVal(ValBank.SCALAR_BOOL, r, 0xFFFF);
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
				b.emitU16(V11IsaTables.OP_INIT_DOMAIN_SPAN, V11IsaTables.SUBOP_INIT_DOMAIN_SPAN_ALL_SPAN, 0, dst, 0, 0,
						0, dom, 0);
				return new TypedVal(ValBank.FRONTIER, dst, dom);
			}
			if ("seed".equals(head)) {
				int dom = resolveDomain(e.elems.get(1).text);
				int dst = allocF();
				if (e.elems.size() == 2) {
					b.emitU16(V11IsaTables.OP_FSEED, V11IsaTables.SUBOP_FSEED_EMPTY, 0, dst, 0, 0, 0, dom, 0);
				} else {
					TypedVal id0 = eval(e.elems.get(2));
					if (id0.domId != 0xFFFF && id0.domId != dom)
						throw new IllegalArgumentException("DOMAIN_MISMATCH: seed id domain mismatch");
					b.emitU16(V11IsaTables.OP_FSEED, V11IsaTables.SUBOP_FSEED_SINGLE_REG, 0, dst, id0.reg, 0, 0, dom,
							0);
					for (int i = 3; i < e.elems.size(); i++) {
						TypedVal idi = eval(e.elems.get(i));
						b.emitU16(V11IsaTables.OP_FSEED, V11IsaTables.SUBOP_FSEED_INSERT_REG, 0, dst, idi.reg, 0, 0,
								dom, 0);
					}
				}
				return new TypedVal(ValBank.FRONTIER, dst, dom);
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
				int wsub = V11IsaTables.SUBOP_WALK_FWD;
				if (":fwd".equals(dir)) {
					wsub = transStar
							? V11IsaTables.SUBOP_WALK_FWD_FIXPOINT_STAR
							: (transPlus ? V11IsaTables.SUBOP_WALK_FWD_FIXPOINT_PLUS : V11IsaTables.SUBOP_WALK_FWD);
				} else if (":rev".equals(dir)) {
					wsub = transStar
							? V11IsaTables.SUBOP_WALK_REV_FIXPOINT_STAR
							: (transPlus ? V11IsaTables.SUBOP_WALK_REV_FIXPOINT_PLUS : V11IsaTables.SUBOP_WALK_REV);
				} else if (":both".equals(dir)) {
					wsub = V11IsaTables.SUBOP_WALK_BOTH;
				}
				int dst = allocF();
				int flags = hasExcl ? V11IsaTables.FLAG_MASKED_S3 : 0;
				int s3 = hasExcl ? exclF.reg : 0;
				b.emitU16(V11IsaTables.OP_WALK, wsub, flags, dst, srcF.reg, 0, s3, rel.relId, 0);
				return new TypedVal(ValBank.FRONTIER, dst, outDom);
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
					case "set-intersect" -> V11IsaTables.SUBOP_SET_OP_INTERSECT;
					case "set-diff" -> V11IsaTables.SUBOP_SET_OP_DIFF;
					case "set-sym-diff" -> V11IsaTables.SUBOP_SET_OP_SYM_DIFF;
					default -> V11IsaTables.SUBOP_SET_OP_UNION;
				};
				int dst = allocF();
				b.emit(V11IsaTables.OP_SET_OP, ssub, 0, dst, a.reg, bv.reg, 0, 0);
				return new TypedVal(ValBank.FRONTIER, dst, a.domId);
			}
			if ("cardinality".equals(head)) {
				TypedVal f = eval(e.elems.get(1));
				if (f.bank != ValBank.FRONTIER)
					throw new IllegalArgumentException("TYPE_MISMATCH: cardinality requires frontier");
				int dst = allocR();
				b.emit(V11IsaTables.OP_SET_CARDINALITY, V11IsaTables.SUBOP_SET_CARDINALITY_CARD_1BIT, 0, dst, f.reg, 0,
						0, 0);
				return new TypedVal(ValBank.SCALAR_U64, dst, 0xFFFF);
			}
			if ("contains?".equals(head)) {
				TypedVal f = eval(e.elems.get(1));
				TypedVal id = eval(e.elems.get(2));
				if (f.bank != ValBank.FRONTIER)
					throw new IllegalArgumentException("TYPE_MISMATCH: contains? requires frontier");
				if (id.domId != 0xFFFF && id.domId != f.domId)
					throw new IllegalArgumentException("DOMAIN_MISMATCH: contains? id domain mismatch");
				int dst = allocR();
				b.emit(V11IsaTables.OP_SET_CONTAINS, V11IsaTables.SUBOP_SET_CONTAINS_CONTAINS_1BIT, 0, dst, f.reg,
						id.reg, 0, 0);
				return new TypedVal(ValBank.SCALAR_BOOL, dst, 0xFFFF);
			}
			if ("+".equals(head) || "-".equals(head) || "*".equals(head) || "/".equals(head)) {
				TypedVal lhs = eval(e.elems.get(1));
				TypedVal rhs = eval(e.elems.get(2));
				boolean li = lhs.bank == ValBank.SCALAR_I64 || lhs.bank == ValBank.SCALAR_U64;
				boolean ri = rhs.bank == ValBank.SCALAR_I64 || rhs.bank == ValBank.SCALAR_U64;
				boolean lf = lhs.bank == ValBank.SCALAR_F64;
				boolean rf = rhs.bank == ValBank.SCALAR_F64;
				if ((li && rf) || (lf && ri)) {
					throw new IllegalArgumentException(
							"TYPE_MISMATCH: implicit int/float mixing forbidden; use (cast ...)");
				}
				int dst = allocR();
				if (li && ri) {
					int asub = switch (head) {
						case "-" -> V11IsaTables.SUBOP_SCALAR_INT_ALU_SUB;
						case "*" -> V11IsaTables.SUBOP_SCALAR_INT_ALU_MUL;
						case "/" -> V11IsaTables.SUBOP_SCALAR_INT_ALU_DIV_S;
						default -> V11IsaTables.SUBOP_SCALAR_INT_ALU_ADD;
					};
					b.emit(V11IsaTables.OP_SCALAR_INT_ALU, asub, 0, dst, lhs.reg, rhs.reg, 0, 0);
					return new TypedVal(ValBank.SCALAR_I64, dst, 0xFFFF);
				}
				int fsub = switch (head) {
					case "-" -> V11IsaTables.SUBOP_SCALAR_FP_ALU_SUB;
					case "*" -> V11IsaTables.SUBOP_SCALAR_FP_ALU_MUL;
					case "/" -> V11IsaTables.SUBOP_SCALAR_FP_ALU_DIV;
					default -> V11IsaTables.SUBOP_SCALAR_FP_ALU_ADD;
				};
				b.emit(V11IsaTables.OP_SCALAR_FP_ALU, fsub, 0, dst, lhs.reg, rhs.reg, 0, 0);
				return new TypedVal(ValBank.SCALAR_F64, dst, 0xFFFF);
			}
			if ("cast".equals(head)) {
				TypedVal v = eval(e.elems.get(1));
				String ty = e.elems.get(2).text;
				int dst = allocR();
				if ("f64".equals(ty) || "f32".equals(ty)) {
					b.emit(V11IsaTables.OP_SCALAR_FP_ALU, V11IsaTables.SUBOP_SCALAR_FP_ALU_I64_TO_F64, 0, dst, v.reg, 0,
							0, 0);
					return new TypedVal(ValBank.SCALAR_F64, dst, 0xFFFF);
				}
				b.emit(V11IsaTables.OP_SCALAR_FP_ALU, V11IsaTables.SUBOP_SCALAR_FP_ALU_F64_TO_I64, 0, dst, v.reg, 0, 0,
						0);
				return new TypedVal(ValBank.SCALAR_I64, dst, 0xFFFF);
			}
			if ("collect".equals(head)) {
				String kind = e.elems.get(1).text;
				String cname = e.elems.get(2).text;
				TypedVal v = eval(e.elems.get(3));
				int slot = outputSlot++;
				outputNames.add(cname);
				int csub = switch (kind) {
					case ":frontier-bitset" -> V11IsaTables.SUBOP_COLLECT_FRONTIER_BITSET;
					case ":frontier-ids" -> V11IsaTables.SUBOP_COLLECT_FRONTIER_IDS;
					case ":vec" -> V11IsaTables.SUBOP_COLLECT_VEC;
					case ":scalar" -> V11IsaTables.SUBOP_COLLECT_SCALAR;
					default -> throw new IllegalArgumentException("PARSE_ERROR: unknown collect kind '" + kind + "'");
				};
				b.emitU16(V11IsaTables.OP_COLLECT, csub, 0, 0, v.reg, 0, 0, slot, 0);
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
					ctx.b.emitU16(V11IsaTables.OP_LOAD_PARAM, V11IsaTables.SUBOP_LOAD_PARAM_SCALAR, 0, r, 0, 0, 0, pid,
							V11IsaTables.SCALAR_U64);
					ctx.env.put(pname, new TypedVal(ValBank.SCALAR_U64, r, dom));
				} else if (ty.kind == Kind.LIST && ty.elems.size() == 2 && "frontier".equals(ty.elems.get(0).text)) {
					int dom = ctx.resolveDomain(ty.elems.get(1).text);
					int f = ctx.allocF();
					ctx.b.emitU16(V11IsaTables.OP_LOAD_PARAM, V11IsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, f, 0, 0, 0,
							pid, dom);
					ctx.env.put(pname, new TypedVal(ValBank.FRONTIER, f, dom));
				} else if (ty.kind == Kind.SYMBOL) {
					int r = ctx.allocR();
					ValBank vb = switch (ty.text) {
						case "u64", "u32" -> ValBank.SCALAR_U64;
						case "f64", "f32" -> ValBank.SCALAR_F64;
						case "bool" -> ValBank.SCALAR_BOOL;
						default -> ValBank.SCALAR_I64;
					};
					int stag = switch (vb) {
						case SCALAR_U64 -> V11IsaTables.SCALAR_U64;
						case SCALAR_F64 -> V11IsaTables.SCALAR_F64;
						case SCALAR_BOOL -> V11IsaTables.SCALAR_BOOL_3VL;
						default -> V11IsaTables.SCALAR_I64;
					};
					ctx.b.emitU16(V11IsaTables.OP_LOAD_PARAM, V11IsaTables.SUBOP_LOAD_PARAM_SCALAR, 0, r, 0, 0, 0, pid,
							stag);
					ctx.env.put(pname, new TypedVal(vb, r, 0xFFFF));
				} else {
					throw new IllegalArgumentException("PARSE_ERROR: unsupported param type");
				}
			} else {
				ctx.eval(form);
			}
		}
		if (!enableOptimizations) {
			ctx.b.emit(V11IsaTables.OP_NOP, 0, 0, 0, 0, 0, 0, 0);
		}
		ctx.b.emit(V11IsaTables.OP_HALT, V11IsaTables.SUBOP_HALT_OK, 0, 0, 0, 0, 0, 0);

		int reqDoms = (snapshot != null && snapshot.getDomainNames() != null) ? snapshot.getDomainNames().size() : 0;
		V11ImpbContainer container = ctx.b.buildImpb(0, 0, reqDoms);
		V11Validator.ValidationResult vr = V11Validator.validate(container.codeWords);
		if (vr.status() != V11IsaTables.IMPULSE_VM_OK) {
			throw new IllegalStateException("VALIDATION_ERROR: invalid v0.11 bytecode at pc=" + vr.badPc());
		}
		return new CompileResult(container, ctx.paramMap, ctx.outputNames);
	}
}
