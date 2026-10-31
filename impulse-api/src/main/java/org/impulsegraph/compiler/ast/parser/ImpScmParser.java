package org.impulsegraph.compiler.ast.parser;

import org.impulsegraph.compiler.ast.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure Java recursive descent S-expression parser for ImpScheme (.impscm).
 */
public final class ImpScmParser {
	private final String src;
	private int cursor;

	public ImpScmParser(String source) {
		this.src = Objects.requireNonNull(source, "source must not be null");
		this.cursor = 0;
	}

	public static ImpScmNode parse(String source) {
		ImpScmParser parser = new ImpScmParser(source);
		return parser.parseNode();
	}

	public ImpScmNode parseNode() {
		skipWhitespaceAndComments();
		if (cursor >= src.length()) {
			return null;
		}

		char c = src.charAt(cursor);
		if (c == '(') {
			return parseList();
		}
		if (c == '"' || c == '\'') {
			return parseString();
		}
		if (c == '#') {
			return parseBool();
		}
		if (Character.isDigit(c)
				|| (c == '-' && cursor + 1 < src.length() && Character.isDigit(src.charAt(cursor + 1)))) {
			return parseNumber();
		}

		return parseSymbol();
	}

	private void skipWhitespaceAndComments() {
		while (cursor < src.length()) {
			char c = src.charAt(cursor);
			if (Character.isWhitespace(c)) {
				cursor++;
			} else if (c == ';') {
				while (cursor < src.length() && src.charAt(cursor) != '\n') {
					cursor++;
				}
			} else {
				break;
			}
		}
	}

	private ImpScmNode parseList() {
		cursor++; // skip '('
		List<ImpScmNode> elements = new ArrayList<>();

		while (true) {
			skipWhitespaceAndComments();
			if (cursor >= src.length()) {
				throw new IllegalArgumentException("Unterminated S-expression: missing ')'");
			}
			if (src.charAt(cursor) == ')') {
				cursor++; // skip ')'
				break;
			}
			ImpScmNode elem = parseNode();
			if (elem != null) {
				elements.add(elem);
			}
		}

		if (elements.isEmpty()) {
			return ScmList.of();
		}

		ImpScmNode head = elements.get(0);
		if (head instanceof ScmSymbol sym) {
			String name = sym.name();
			switch (name) {
				case "program" -> {
					List<ImpScmNode> steps = elements.subList(1, elements.size());
					return new ScmProgram(steps);
				}
				case "csr-walk", "csc-walk", "walk" -> {
					ScmWalk.Direction dir = "csr-walk".equals(name)
							? ScmWalk.Direction.FORWARD_CSR
							: "csc-walk".equals(name) ? ScmWalk.Direction.REVERSE_CSC : ScmWalk.Direction.AUTO;
					String relName = "";
					int relId = -1;
					List<ImpScmNode> shaders = new ArrayList<>();
					List<ImpScmNode> subSteps = new ArrayList<>();

					for (int i = 1; i < elements.size(); i++) {
						ImpScmNode item = elements.get(i);
						if (item instanceof ScmLiteral.ScmString s && relName.isEmpty() && relId == -1) {
							relName = s.value();
						} else if (item instanceof ScmLiteral.ScmInt id && relId == -1 && relName.isEmpty()) {
							relId = (int) id.value();
						} else if (item instanceof ScmVectorFilter vf) {
							shaders.add(vf.predicate());
						} else if (item instanceof ScmList shList && !shList.elements().isEmpty()
								&& shList.elements().get(0) instanceof ScmSymbol symShader
								&& symShader.name().equals("shader")) {
							for (int j = 1; j < shList.elements().size(); j++) {
								shaders.add(shList.elements().get(j));
							}
						} else {
							subSteps.add(item);
						}
					}
					return new ScmWalk(relName, relId, dir, shaders, subSteps);
				}
				case "vector-filter" -> {
					if (elements.size() > 1) {
						return new ScmVectorFilter(elements.get(1));
					}
				}
				case "reduce-sum" -> {
					return ScmReduce.sum();
				}
				case "reduce-first" -> {
					return ScmReduce.first();
				}
				case "reduce-count" -> {
					return ScmReduce.count();
				}
				case "collect-bitset" -> {
					return ScmCollect.bitset();
				}
				case "collect-vector" -> {
					return ScmCollect.vector();
				}
				case "collect-list" -> {
					return ScmCollect.list();
				}
				case "collect-scalar" -> {
					return ScmCollect.scalar();
				}
				case "collect-distinct" -> {
					return ScmCollect.distinct();
				}
				case "collect-keys" -> {
					return ScmCollect.keys();
				}
				case "collect-value_map", "collect-value-map" -> {
					return ScmCollect.valueMap();
				}
				case "lane-init-seeds" -> {
					List<Long> seeds = extractLongList(elements.size() > 1 ? elements.get(1) : null);
					int maxNodes = elements.size() > 2 ? extractInt(elements.get(2), 0) : 0;
					return new ScmLaneInitSeeds(seeds, maxNodes);
				}
				case "lane-init-groups" -> {
					List<List<Long>> groups = new ArrayList<>();
					if (elements.size() > 1 && elements.get(1) instanceof ScmList gList) {
						for (ImpScmNode g : gList.elements()) {
							groups.add(extractLongList(g));
						}
					}
					int maxNodes = elements.size() > 2 ? extractInt(elements.get(2), 0) : 0;
					return new ScmLaneInitGroups(groups, maxNodes);
				}
				case "lane-set-edge-mask" -> {
					String relName = "";
					int relId = -1;
					if (elements.size() > 1) {
						if (elements.get(1) instanceof ScmLiteral.ScmString s) {
							relName = s.value();
						} else if (elements.get(1) instanceof ScmLiteral.ScmInt n) {
							relId = (int) n.value();
						}
					}
					List<Long> tripped = elements.size() > 2 ? extractLongList(elements.get(2)) : List.of();
					boolean packed = elements.size() > 3 && extractBool(elements.get(3), false);
					return new ScmLaneSetEdgeMask(relName, relId, tripped, packed);
				}
				case "lane-csr-walk", "lane-walk-csr", "lane-csc-walk", "lane-walk-csc" -> {
					ScmWalk.Direction dir = (name.contains("csc"))
							? ScmWalk.Direction.REVERSE_CSC
							: ScmWalk.Direction.FORWARD_CSR;
					String relName = "";
					int relId = -1;
					if (elements.size() > 1) {
						if (elements.get(1) instanceof ScmLiteral.ScmString s) {
							relName = s.value();
						} else if (elements.get(1) instanceof ScmLiteral.ScmInt n) {
							relId = (int) n.value();
						} else if (elements.get(1) instanceof ScmSymbol s) {
							relName = s.name();
						}
					}
					boolean acc = elements.size() > 2 && extractBool(elements.get(2), false);
					boolean fp = elements.size() > 3 && extractBool(elements.get(3), false);
					return new ScmLaneWalk(relName, relId, dir, acc, fp);
				}
				case "lane-prune-mask" -> {
					int mode = elements.size() > 1 ? extractInt(elements.get(1), 0) : 0;
					int arg1 = elements.size() > 2 ? extractInt(elements.get(2), 0) : 0;
					int arg2 = elements.size() > 3 ? extractInt(elements.get(3), 0) : 0;
					List<Long> deny = elements.size() > 4 ? extractLongList(elements.get(4)) : List.of();
					return new ScmLanePruneMask(mode, arg1, arg2, "", -1, deny);
				}
				case "lane-filter-quorum" -> {
					int k = elements.size() > 1 ? extractInt(elements.get(1), 1) : 1;
					boolean inv = elements.size() > 2 && extractBool(elements.get(2), false);
					return new ScmLaneFilterQuorum(k, inv);
				}
				case "lane-reduce-and" -> {
					int mode = elements.size() > 1 ? extractInt(elements.get(1), 0) : 0;
					return new ScmLaneReduceAnd(mode);
				}
				case "lane-reduce-attr" -> {
					int attrId = 0;
					String attrName = "";
					if (elements.size() > 1) {
						if (elements.get(1) instanceof ScmLiteral.ScmString s) {
							attrName = s.value();
						} else {
							attrId = extractInt(elements.get(1), 0);
						}
					}
					int redOp = elements.size() > 2 ? extractInt(elements.get(2), 0) : 0;
					boolean fromReg = elements.size() > 3 && extractBool(elements.get(3), false);
					return new ScmLaneReduceAttr(attrId, attrName, redOp, fromReg);
				}
				case "lane-set-cover" -> {
					int maxN = elements.size() > 1 ? extractInt(elements.get(1), 4) : 4;
					long mask = elements.size() > 2 ? extractUnsignedLong(elements.get(2), ~0L) : ~0L;
					boolean pairMode = elements.size() > 3 && extractBool(elements.get(3), false);
					return new ScmLaneSetCover(maxN, mask, pairMode);
				}
				case "vector-ann-search" -> {
					float[] q = elements.size() > 1 ? extractFloatArray(elements.get(1)) : new float[0];
					int topK = elements.size() > 2 ? extractInt(elements.get(2), 1) : 1;
					int idxId = elements.size() > 3 ? extractInt(elements.get(3), 0) : 0;
					boolean ml = elements.size() > 4 && extractBool(elements.get(4), false);
					return new ScmVectorAnnSearch(q, topK, idxId, "", ml);
				}
				case "vector-rerank-dot" -> {
					float[] q = elements.size() > 1 ? extractFloatArray(elements.get(1)) : new float[0];
					int attrId = elements.size() > 2 ? extractInt(elements.get(2), 0) : 0;
					int metric = elements.size() > 3 ? extractInt(elements.get(3), 0) : 0;
					return new ScmVectorRerankDot(q, attrId, "", metric);
				}
				case "fixpoint" -> {
					String relName = "";
					int relId = -1;
					if (elements.size() > 1) {
						if (elements.get(1) instanceof ScmLiteral.ScmString s) {
							relName = s.value();
						} else if (elements.get(1) instanceof ScmLiteral.ScmInt n) {
							relId = (int) n.value();
						} else if (elements.get(1) instanceof ScmSymbol s) {
							relName = s.name();
						}
					}
					int maxIter = elements.size() > 2 ? extractInt(elements.get(2), 100) : 100;
					ScmWalk.Direction dir = ScmWalk.Direction.FORWARD_CSR;
					List<ImpScmNode> body = new ArrayList<>();
					for (int i = 3; i < elements.size(); i++) {
						ImpScmNode el = elements.get(i);
						if (el instanceof ScmSymbol s && s.name().equals(":csc")) {
							dir = ScmWalk.Direction.REVERSE_CSC;
						} else {
							body.add(el);
						}
					}
					return new ScmFixpoint(relName, relId, dir, maxIter, body);
				}
				case "set-union", "set-intersect", "set-diff" -> {
					ScmSetOp.Kind kind = "set-union".equals(name)
							? ScmSetOp.Kind.UNION
							: "set-intersect".equals(name) ? ScmSetOp.Kind.INTERSECT : ScmSetOp.Kind.DIFF;
					ImpScmNode rhs = elements.size() > 1 ? elements.get(1) : ScmList.of();
					return new ScmSetOp(kind, rhs);
				}
				default -> {
				}
			}
		}

		return new ScmList(elements);
	}

	private ScmLiteral.ScmString parseString() {
		char quote = src.charAt(cursor++);
		StringBuilder sb = new StringBuilder();
		while (cursor < src.length()) {
			char c = src.charAt(cursor++);
			if (c == quote)
				break;
			if (c == '\\' && cursor < src.length()) {
				char esc = src.charAt(cursor++);
				switch (esc) {
					case 'n' -> sb.append('\n');
					case 't' -> sb.append('\t');
					case 'r' -> sb.append('\r');
					case '\\' -> sb.append('\\');
					case '"' -> sb.append('"');
					case '\'' -> sb.append('\'');
					default -> sb.append(esc);
				}
			} else {
				sb.append(c);
			}
		}
		return ScmLiteral.ofString(sb.toString());
	}

	private ScmLiteral.ScmBool parseBool() {
		cursor++; // skip '#'
		if (cursor < src.length()) {
			char c = src.charAt(cursor++);
			if (c == 't' || c == 'T')
				return ScmLiteral.ofBool(true);
			if (c == 'f' || c == 'F')
				return ScmLiteral.ofBool(false);
		}
		return ScmLiteral.ofBool(false);
	}

	private ImpScmNode parseNumber() {
		int start = cursor;
		if (src.charAt(cursor) == '-')
			cursor++;
		boolean isFloat = false;

		while (cursor < src.length()) {
			char c = src.charAt(cursor);
			if (Character.isDigit(c)) {
				cursor++;
			} else if (c == '.' && !isFloat && cursor + 1 < src.length() && Character.isDigit(src.charAt(cursor + 1))) {
				isFloat = true;
				cursor += 2;
			} else if (c == 'e' || c == 'E') {
				isFloat = true;
				cursor++;
				if (cursor < src.length() && (src.charAt(cursor) == '+' || src.charAt(cursor) == '-')) {
					cursor++;
				}
			} else {
				break;
			}
		}

		String numText = src.substring(start, cursor);
		if (isFloat) {
			return ScmLiteral.ofFloat(Double.parseDouble(numText));
		} else {
			try {
				return ScmLiteral.ofInt(Long.parseLong(numText));
			} catch (NumberFormatException e) {
				return ScmLiteral.ofInt(Long.parseUnsignedLong(numText));
			}
		}
	}

	private static List<Long> extractLongList(ImpScmNode node) {
		if (node instanceof ScmList list) {
			List<Long> out = new ArrayList<>(list.elements().size());
			for (ImpScmNode el : list.elements()) {
				if (el instanceof ScmLiteral.ScmInt n) {
					out.add(n.value());
				}
			}
			return out;
		}
		if (node instanceof ScmLiteral.ScmInt n) {
			return List.of(n.value());
		}
		return List.of();
	}

	private static float[] extractFloatArray(ImpScmNode node) {
		if (node instanceof ScmList list) {
			float[] out = new float[list.elements().size()];
			int idx = 0;
			for (ImpScmNode el : list.elements()) {
				if (el instanceof ScmLiteral.ScmFloat f) {
					out[idx++] = (float) f.value();
				} else if (el instanceof ScmLiteral.ScmInt n) {
					out[idx++] = (float) n.value();
				}
			}
			return idx == out.length ? out : java.util.Arrays.copyOf(out, idx);
		}
		return new float[0];
	}

	private static int extractInt(ImpScmNode node, int defaultVal) {
		if (node instanceof ScmLiteral.ScmInt n) {
			return (int) n.value();
		}
		return defaultVal;
	}

	private static long extractUnsignedLong(ImpScmNode node, long defaultVal) {
		if (node instanceof ScmLiteral.ScmInt n) {
			return n.value();
		}
		return defaultVal;
	}

	private static boolean extractBool(ImpScmNode node, boolean defaultVal) {
		if (node instanceof ScmLiteral.ScmBool b) {
			return b.value();
		}
		if (node instanceof ScmLiteral.ScmInt n) {
			return n.value() != 0L;
		}
		return defaultVal;
	}

	private ScmSymbol parseSymbol() {
		int start = cursor;
		while (cursor < src.length()) {
			char c = src.charAt(cursor);
			if (Character.isWhitespace(c) || c == '(' || c == ')' || c == ';' || c == '"' || c == '\'') {
				break;
			}
			cursor++;
		}
		String sym = src.substring(start, cursor);
		return ScmSymbol.of(sym);
	}
}
