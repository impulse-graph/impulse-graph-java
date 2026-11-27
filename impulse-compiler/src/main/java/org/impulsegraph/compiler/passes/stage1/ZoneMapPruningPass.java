package org.impulsegraph.compiler.passes.stage1;

import org.impulsegraph.api.Decimal64;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.stats.AttributeStatistics;
import org.impulsegraph.api.stats.GraphStatistics;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.ast.algebra.AlgebraicSignature;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.impscm.v11.DecimalBoundaryFolder;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 1 Pass: Zone Map Pruning and Dead Code Elimination. Evaluates
 * predicates against precomputed attribute zone maps: 1. If predicate is
 * provably FALSE (e.g. u.age > 250 when max(age)==114), prunes traversal
 * branch. 2. If predicate is provably TRUE for 100% of rows, strips the filter
 * from the inner traversal loop.
 */
public final class ZoneMapPruningPass implements CompilerPass {

	public static final ZoneMapPruningPass INSTANCE = new ZoneMapPruningPass();

	@Override
	public String name() {
		return "ZoneMapPruningPass";
	}

	@Override
	public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
		if (ast == null)
			return null;
		ImpulseGraphSnapshot snapshot = context.snapshot();
		GraphStatistics stats = snapshot != null ? snapshot.getGraphStatistics() : null;
		return pruneScm(ast, stats, snapshot);
	}

	private ImpScmNode pruneScm(ImpScmNode node, GraphStatistics stats, ImpulseGraphSnapshot snapshot) {
		if (node instanceof ScmProgram prog) {
			List<ImpScmNode> prunedSteps = new ArrayList<>();
			for (ImpScmNode step : prog.steps()) {
				ImpScmNode p = pruneScm(step, stats, snapshot);
				if (p != null) {
					prunedSteps.add(p);
				}
			}
			return new ScmProgram(prunedSteps);
		}

		if (node instanceof ScmCelExpr celExpr) {
			CelAstNode cel = (CelAstNode) celExpr.celAst();
			if (cel != null) {
				CelAstNode prunedCel = pruneCel(cel, stats, snapshot);
				if (prunedCel.signature() != null && prunedCel.signature().isConstantKnown()) {
					if (!prunedCel.signature().constantBoolVal()) {
						return ScmSymbol.of("constant-false");
					} else {
						return ScmSymbol.of("constant-true");
					}
				}
				if (prunedCel.kind() == CelAstNode.Kind.LITERAL_BOOL) {
					return prunedCel.boolVal() ? ScmSymbol.of("constant-true") : ScmSymbol.of("constant-false");
				}
				return new ScmCelExpr(celExpr.rawText(), prunedCel);
			}
			return celExpr;
		}

		if (node instanceof ScmList list && list.elements().size() >= 3) {
			ImpScmNode folded = foldScmListPredicate(list, stats, snapshot);
			if (folded != null) {
				return folded;
			}
		}

		if (node instanceof ScmWalk walk) {
			List<ImpScmNode> filter = walk.shaderSteps();
			if (!filter.isEmpty()) {
				List<ImpScmNode> prunedFilter = new ArrayList<>();
				for (ImpScmNode f : filter) {
					ImpScmNode p = pruneScm(f, stats, snapshot);
					if (isConstantFalse(p)) {
						prunedFilter = List.of(p);
						break;
					}
					if (!isConstantTrue(p) && p != null)
						prunedFilter.add(p);
				}
				List<ImpScmNode> subs = new ArrayList<>();
				for (ImpScmNode sub : walk.subSteps()) {
					ImpScmNode ps = pruneScm(sub, stats, snapshot);
					if (ps != null)
						subs.add(ps);
				}
				return new ScmWalk(walk.relationName(), walk.relationId(), walk.direction(), prunedFilter, subs);
			}
			return walk;
		}

		if (node instanceof ScmVectorFilter vf) {
			ImpScmNode pruned = pruneScm(vf.predicate(), stats, snapshot);
			if (isConstantFalse(pruned)) {
				return new ScmVectorFilter(pruned);
			}
			if (isConstantTrue(pruned)) {
				return null; // Stripped completely
			}
			return new ScmVectorFilter(pruned);
		}

		return node;
	}

	private record ExactI64ZoneBounds(long min, long max, boolean hasNulls, boolean isDecimal, int precision,
			int scale) {
	}

	private static ExactI64ZoneBounds resolveExactI64Bounds(String attrName, GraphStatistics stats,
			ImpulseGraphSnapshot snapshot) {
		boolean isDecimal = false;
		int prec = 18;
		int scale = 0;
		boolean attrNullable = false;
		long zmMin = Long.MAX_VALUE;
		long zmMax = Long.MIN_VALUE;
		boolean havePhysicalZm = false;

		if (snapshot instanceof GraphSnapshot gs) {
			for (String domName : gs.getDomainNames()) {
				BinarySnapshotLoader.LoadedDomain ld = gs.getDomain(domName);
				if (ld == null)
					continue;
				int idx = ld.getAttributeIndex(attrName);
				if (idx < 0)
					continue;
				BinarySnapshotLoader.LoadedAttribute la = ld.getAttribute(idx);
				if (la != null) {
					attrNullable = la.isNullable();
					if ((la.typeCode() & 0x7F) == 0x15) {
						isDecimal = true;
						prec = la.precision();
						scale = la.scale();
					}
				}
				for (BinarySnapshotLoader.LoadedIndex sidx : gs.getSecondaryIndices()) {
					if (sidx.relationId() == 0xFFFF && sidx.domainId() == ld.domainId() && sidx.attributeIndex() == idx
							&& sidx.indexType() == (byte) 0x02) {
						MemorySegment seg = sidx.dataSegment();
						if (seg != null && !seg.equals(MemorySegment.NULL) && seg.byteSize() >= 24L) {
							ValueLayout.OfInt leInt = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
							ValueLayout.OfLong leLong = ValueLayout.JAVA_LONG_UNALIGNED
									.withOrder(ByteOrder.LITTLE_ENDIAN);
							int pageCount = seg.get(leInt, 12L);
							byte tc = seg.get(ValueLayout.JAVA_BYTE, 16L);
							boolean exactI64 = (tc & 0x7F) == 0x15 || (sidx.payloadFeatureMask() & 1L) != 0L;
							if (exactI64 && 24L + (long) pageCount * 16L <= seg.byteSize()) {
								for (int p = 0; p < pageCount; p++) {
									long pMin = seg.get(leLong, 24L + ((long) p << 4));
									long pMax = seg.get(leLong, 32L + ((long) p << 4));
									if (pMin <= pMax) {
										if (pMin < zmMin)
											zmMin = pMin;
										if (pMax > zmMax)
											zmMax = pMax;
									}
								}
								havePhysicalZm = (pageCount > 0);
							}
						}
						break;
					}
				}
				break;
			}
		}

		AttributeStatistics attrStats = stats != null ? stats.getAttributeStatistics(attrName) : null;
		if (attrStats != null && attrStats.minIntVal() <= attrStats.maxIntVal()) {
			boolean hasNulls = attrNullable || attrStats.hasNulls() || attrStats.nullCount() > 0;
			return new ExactI64ZoneBounds(attrStats.minIntVal(), attrStats.maxIntVal(), hasNulls, isDecimal, prec,
					scale);
		}
		if (havePhysicalZm) {
			return new ExactI64ZoneBounds(zmMin, zmMax, attrNullable, isDecimal, prec, scale);
		}
		return null;
	}

	private static Boolean evaluateExactI64Pruning(DecimalBoundaryFolder.CmpOp op, long val, long min, long max,
			boolean hasNulls) {
		if (min > max) {
			// Empty/all-NULL column: every comparison evaluates to FALSE/UNKNOWN (no rows
			// match)
			return Boolean.FALSE;
		}
		return switch (op) {
			case GE -> {
				if (val > max)
					yield Boolean.FALSE;
				if (!hasNulls && val <= min)
					yield Boolean.TRUE;
				yield null;
			}
			case GT -> {
				if (val >= max)
					yield Boolean.FALSE;
				if (!hasNulls && val < min)
					yield Boolean.TRUE;
				yield null;
			}
			case LE -> {
				if (val < min)
					yield Boolean.FALSE;
				if (!hasNulls && val >= max)
					yield Boolean.TRUE;
				yield null;
			}
			case LT -> {
				if (val <= min)
					yield Boolean.FALSE;
				if (!hasNulls && val > max)
					yield Boolean.TRUE;
				yield null;
			}
			case NE -> {
				if (!hasNulls && (val < min || val > max))
					yield Boolean.TRUE;
				yield null;
			}
			case EQ -> {
				if (val < min || val > max)
					yield Boolean.FALSE;
				yield null;
			}
		};
	}

	private static DecimalBoundaryFolder.CmpOp parseScmCmpOp(String op) {
		if (op.contains("gte") || op.contains(">="))
			return DecimalBoundaryFolder.CmpOp.GE;
		if (op.contains("gt") || op.contains(">"))
			return DecimalBoundaryFolder.CmpOp.GT;
		if (op.contains("lte") || op.contains("<="))
			return DecimalBoundaryFolder.CmpOp.LE;
		if (op.contains("lt") || op.contains("<"))
			return DecimalBoundaryFolder.CmpOp.LT;
		if (op.contains("neq") || op.contains("!="))
			return DecimalBoundaryFolder.CmpOp.NE;
		if (op.contains("eq") || op.contains("=="))
			return DecimalBoundaryFolder.CmpOp.EQ;
		return null;
	}

	private ImpScmNode foldScmListPredicate(ScmList list, GraphStatistics stats, ImpulseGraphSnapshot snapshot) {
		if ((stats == null && snapshot == null) || list.elements().size() < 3)
			return null;

		String op = list.elements().get(0).toScmString();
		ImpScmNode lhs = list.elements().get(1);
		ImpScmNode rhs = list.elements().get(2);

		String attrName = extractAttrName(lhs);
		if (attrName == null)
			return null;

		DecimalBoundaryFolder.CmpOp cmpOp = parseScmCmpOp(op);
		if (cmpOp == null)
			return null;

		ExactI64ZoneBounds bounds = resolveExactI64Bounds(attrName, stats, snapshot);
		if (bounds == null)
			return null;

		if (rhs instanceof ScmLiteral.ScmInt intLit) {
			if (bounds.isDecimal()) {
				DecimalBoundaryFolder.FoldedComparison fc = DecimalBoundaryFolder.foldComparison(cmpOp,
						bounds.precision(), bounds.scale(), intLit.value(), 0);
				return applyFoldedDecimalPruning(fc, bounds);
			}
			Boolean folded = evaluateExactI64Pruning(cmpOp, intLit.value(), bounds.min(), bounds.max(),
					bounds.hasNulls());
			return folded != null ? ScmLiteral.ofBool(folded) : null;
		}

		// Exact decimal literal form: (dec "digits" P S)
		if (rhs instanceof ScmList decList && decList.elements().size() == 4
				&& "dec".equals(decList.elements().get(0).toScmString())
				&& decList.elements().get(1) instanceof ScmLiteral.ScmString digitsLit
				&& decList.elements().get(2) instanceof ScmLiteral.ScmInt pLit
				&& decList.elements().get(3) instanceof ScmLiteral.ScmInt sLit) {
			int lp = (int) pLit.value();
			int ls = (int) sLit.value();
			long litUnscaled = Decimal64.parseUnscaledExact(digitsLit.value(), lp, ls);
			int colP = bounds.isDecimal() ? bounds.precision() : 18;
			int colS = bounds.isDecimal() ? bounds.scale() : 0;
			DecimalBoundaryFolder.FoldedComparison fc = DecimalBoundaryFolder.foldComparison(cmpOp, colP, colS,
					litUnscaled, ls);
			return applyFoldedDecimalPruning(fc, bounds);
		}

		return null;
	}

	private static ImpScmNode applyFoldedDecimalPruning(DecimalBoundaryFolder.FoldedComparison fc,
			ExactI64ZoneBounds bounds) {
		if (fc.kind() == DecimalBoundaryFolder.FoldKind.ALWAYS_FALSE) {
			return ScmLiteral.ofBool(false);
		}
		if (fc.kind() == DecimalBoundaryFolder.FoldKind.ALWAYS_TRUE) {
			return bounds.hasNulls() ? null : ScmLiteral.ofBool(true);
		}
		Boolean folded = evaluateExactI64Pruning(fc.op(), fc.thresholdUnscaled(), bounds.min(), bounds.max(),
				bounds.hasNulls());
		return folded != null ? ScmLiteral.ofBool(folded) : null;
	}

	private String extractAttrName(ImpScmNode node) {
		if (node instanceof ScmList list && list.elements().size() >= 3) {
			String sym = list.elements().get(0).toScmString();
			if (sym.contains("get-attr")) {
				String raw = list.elements().get(2).toScmString();
				return raw.replace("\"", "").trim();
			}
		}
		return null;
	}

	public CelAstNode pruneCel(CelAstNode node) {
		return pruneCel(node, null, null);
	}

	public CelAstNode pruneCel(CelAstNode node, GraphStatistics stats, ImpulseGraphSnapshot snapshot) {
		if (node == null)
			return null;

		// Top-level constant fold (only for boolean/predicate expressions, never
		// numeric/string literals or arithmetic expressions)
		AlgebraicSignature sig = node.signature();
		if (sig != null && sig.isConstantKnown() && isBooleanExpr(node, sig)) {
			return CelAstNode.makeBool(sig.constantBoolVal());
		}

		List<CelAstNode> children = new ArrayList<>();
		for (CelAstNode child : node.children()) {
			children.add(pruneCel(child, stats, snapshot));
		}

		CelAstNode result = node.withChildren(children);

		// Exact DECIMAL64 / LITERAL_DECIMAL zone-map pruning (zero double precision
		// loss)
		if (result.kind() == CelAstNode.Kind.BINARY_OP && children.size() >= 2 && (stats != null || snapshot != null)) {
			Boolean decFolded = tryPruneCelDecimalComparison(result.text(), children.get(0), children.get(1), stats,
					snapshot);
			if (decFolded != null) {
				return CelAstNode.makeBool(decFolded);
			}
		}

		// Boolean absorptive pruning
		if (result.kind() == CelAstNode.Kind.BINARY_OP) {
			String op = result.text();
			if (children.size() >= 2) {
				CelAstNode left = children.get(0);
				CelAstNode right = children.get(1);

				if ("&&".equals(op)) {
					if (isFalse(left) || isFalse(right))
						return CelAstNode.makeBool(false);
					if (isTrue(left))
						return right;
					if (isTrue(right))
						return left;
				} else if ("||".equals(op)) {
					if (isTrue(left) || isTrue(right))
						return CelAstNode.makeBool(true);
					if (isFalse(left))
						return right;
					if (isFalse(right))
						return left;
				}
			}
		}

		return result;
	}

	private static Boolean tryPruneCelDecimalComparison(String op, CelAstNode left, CelAstNode right,
			GraphStatistics stats, ImpulseGraphSnapshot snapshot) {
		DecimalBoundaryFolder.CmpOp cmpOp = switch (op) {
			case "==" -> DecimalBoundaryFolder.CmpOp.EQ;
			case "!=" -> DecimalBoundaryFolder.CmpOp.NE;
			case "<" -> DecimalBoundaryFolder.CmpOp.LT;
			case "<=" -> DecimalBoundaryFolder.CmpOp.LE;
			case ">" -> DecimalBoundaryFolder.CmpOp.GT;
			case ">=" -> DecimalBoundaryFolder.CmpOp.GE;
			default -> null;
		};
		if (cmpOp == null) {
			return null;
		}
		String attrName = extractCelAttrName(left);
		String decText = extractCelDecimalText(right);
		if (attrName == null || decText == null) {
			// Try flipped orientation: literal <op> attr
			attrName = extractCelAttrName(right);
			decText = extractCelDecimalText(left);
			if (attrName == null || decText == null) {
				return null;
			}
			cmpOp = switch (cmpOp) {
				case LT -> DecimalBoundaryFolder.CmpOp.GT;
				case LE -> DecimalBoundaryFolder.CmpOp.GE;
				case GT -> DecimalBoundaryFolder.CmpOp.LT;
				case GE -> DecimalBoundaryFolder.CmpOp.LE;
				case EQ, NE -> cmpOp;
			};
		}
		ExactI64ZoneBounds bounds = resolveExactI64Bounds(attrName, stats, snapshot);
		if (bounds == null) {
			return null;
		}
		int[] ps = parseDecimalLiteralPrecisionScale(decText);
		if (ps == null) {
			return null;
		}
		String cleanDec = decText.trim();
		if (cleanDec.endsWith("d") || cleanDec.endsWith("D")) {
			cleanDec = cleanDec.substring(0, cleanDec.length() - 1);
		}
		long litUnscaled = Decimal64.parseUnscaledExact(cleanDec, ps[0], ps[1]);
		int colP = bounds.isDecimal() ? bounds.precision() : 18;
		int colS = bounds.isDecimal() ? bounds.scale() : 0;
		DecimalBoundaryFolder.FoldedComparison fc = DecimalBoundaryFolder.foldComparison(cmpOp, colP, colS, litUnscaled,
				ps[1]);
		if (fc.kind() == DecimalBoundaryFolder.FoldKind.ALWAYS_FALSE) {
			return Boolean.FALSE;
		}
		if (fc.kind() == DecimalBoundaryFolder.FoldKind.ALWAYS_TRUE) {
			return bounds.hasNulls() ? null : Boolean.TRUE;
		}
		return evaluateExactI64Pruning(fc.op(), fc.thresholdUnscaled(), bounds.min(), bounds.max(), bounds.hasNulls());
	}

	private static String extractCelAttrName(CelAstNode node) {
		if (node.kind() == CelAstNode.Kind.MEMBER_ACCESS || node.kind() == CelAstNode.Kind.IDENTIFIER) {
			return node.text();
		}
		return null;
	}

	private static String extractCelDecimalText(CelAstNode node) {
		if (node.kind() == CelAstNode.Kind.LITERAL_DECIMAL) {
			return node.text();
		}
		if (node.kind() == CelAstNode.Kind.UNARY_OP && "-".equals(node.text()) && node.children().size() == 1
				&& node.children().get(0).kind() == CelAstNode.Kind.LITERAL_DECIMAL) {
			String raw = node.children().get(0).text();
			return raw.startsWith("-") ? raw.substring(1) : ("-" + raw);
		}
		return null;
	}

	private static int[] parseDecimalLiteralPrecisionScale(String text) {
		if (text == null || text.isEmpty()) {
			return null;
		}
		String s = text.trim();
		if (s.endsWith("d") || s.endsWith("D")) {
			s = s.substring(0, s.length() - 1);
		}
		if (s.startsWith("+") || s.startsWith("-")) {
			s = s.substring(1);
		}
		int dot = s.indexOf('.');
		int scale = (dot >= 0) ? (s.length() - dot - 1) : 0;
		String digits = (dot >= 0) ? (s.substring(0, dot) + s.substring(dot + 1)) : s;
		int firstNonZero = 0;
		while (firstNonZero < digits.length() - 1 && digits.charAt(firstNonZero) == '0') {
			firstNonZero++;
		}
		int sigDigits = digits.length() - firstNonZero;
		int precision = Math.max(1, Math.max(sigDigits, scale));
		if (precision > 18 || scale < 0 || scale > precision) {
			return null;
		}
		return new int[]{precision, scale};
	}

	private boolean isBooleanExpr(CelAstNode node, AlgebraicSignature sig) {
		return switch (node.kind()) {
			case LITERAL_BOOL -> true;
			case UNARY_OP -> "!".equals(node.text());
			case BINARY_OP -> switch (node.text()) {
				case "&&", "||", "==", "!=", "<", "<=", ">", ">=" -> true;
				default -> false;
			};
			case LITERAL_INT, LITERAL_FLOAT, LITERAL_DECIMAL, LITERAL_STRING, LIST_LITERAL -> false;
			default -> sig.interval() == null || !sig.interval().isBounded();
		};
	}

	private boolean isTrue(CelAstNode node) {
		return node.kind() == CelAstNode.Kind.LITERAL_BOOL && node.boolVal();
	}

	private boolean isFalse(CelAstNode node) {
		return node.kind() == CelAstNode.Kind.LITERAL_BOOL && !node.boolVal();
	}

	public boolean isConstantFalse(ImpScmNode node) {
		if (node instanceof ScmSymbol s)
			return "constant-false".equals(s.name()) || "#f".equals(s.name());
		if (node instanceof ScmLiteral.ScmBool b)
			return !b.value();
		return false;
	}

	private boolean isConstantTrue(ImpScmNode node) {
		if (node instanceof ScmSymbol s)
			return "constant-true".equals(s.name()) || "#t".equals(s.name());
		if (node instanceof ScmLiteral.ScmBool b)
			return b.value();
		return false;
	}
}
