package org.impulsegraph.compiler.emitter;

import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter.InstructionWord;
import org.impulsegraph.vm.VmRegisterType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production zero-dependency ImpAsm ({@code .impas}) text assembler.
 *
 * <p>
 * Produces {@link InstructionWord} sequences byte-compatible with
 * {@code impulse-graph-core/impulse-cli} ({@code assemble.rs}) and supports all
 * v0.10.0 opcodes ({@code 0x00..0xCB}), labels, relative jump offsets,
 * {@code .const}/{@code .equ}/{@code .domain}/{@code .relation}/{@code .rel}/{@code .attribute}
 * directives, inline {@code .data} blocks ({@code .csr_inline},
 * {@code .array_float}, {@code .array_int}, {@code .set}, etc.), named and
 * positional operands, flags, pipe-packed payloads, and GraphBLAS constants.
 */
public final class ImpAsmAssembler {

	/**
	 * Result of assembling an ImpAsm ({@code .impas}) source program.
	 */
	public record AssembledProgram(List<InstructionWord> instructions, byte[] inlineData, Map<String, Integer> labels,
			Map<String, Integer> symbols) {

		public AssembledProgram {
			instructions = instructions != null ? List.copyOf(instructions) : List.of();
			inlineData = inlineData != null ? inlineData.clone() : new byte[0];
			labels = labels != null ? Collections.unmodifiableMap(new LinkedHashMap<>(labels)) : Map.of();
			symbols = symbols != null ? Collections.unmodifiableMap(new LinkedHashMap<>(symbols)) : Map.of();
		}

		@Override
		public byte[] inlineData() {
			return inlineData.clone();
		}
	}

	/**
	 * Exception raised when ImpAsm assembly fails, carrying the 1-based source line
	 * number.
	 */
	public static final class AssemblyException extends RuntimeException {

		private final int lineNumber;

		public AssemblyException(int lineNumber, String message) {
			super("Line " + lineNumber + ": " + message);
			this.lineNumber = lineNumber;
		}

		public AssemblyException(int lineNumber, String message, Throwable cause) {
			super("Line " + lineNumber + ": " + message, cause);
			this.lineNumber = lineNumber;
		}

		public int lineNumber() {
			return lineNumber;
		}
	}

	private record RawInstruction(String opcodeStr, String flagsStr, String dstStr, String payloadStr, int lineNumber) {
	}

	private static final Map<String, Byte> OPCODE_TABLE = buildOpcodeTable();
	private static final Map<String, Integer> CONSTANT_TABLE = buildConstantTable();

	private static final Pattern HEX_PC_PREFIX = Pattern.compile("^0[xX][0-9a-fA-F]+\\s*:(.*)$");
	private static final Pattern LABEL_PREFIX = Pattern.compile("^([A-Za-z_][A-Za-z0-9_$.-]*)\\s*:(.*)$");
	private static final Pattern ARRAY_FLOAT_PATTERN = Pattern
			.compile("^\\.array_float\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\\[(.*)\\]$");
	private static final Pattern ARRAY_INT_PATTERN = Pattern.compile(
			"^\\.(?:array_int|array_uint32|array_node|set|set_dense|set_bitset|set_roaring)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\\[(.*)\\]$");
	private static final Pattern CSR_INLINE_START = Pattern
			.compile("^\\.csr_inline\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\\{(.*)$");

	private ImpAsmAssembler() {
	}

	/**
	 * Assembles an ImpAsm ({@code .impas}) file from disk.
	 */
	public static AssembledProgram assemble(Path impasFile) throws IOException {
		Objects.requireNonNull(impasFile, "impasFile must not be null");
		return assemble(Files.readString(impasFile));
	}

	/**
	 * Assembles ImpAsm ({@code .impas}) text into an {@link AssembledProgram}.
	 */
	public static AssembledProgram assemble(String impasText) {
		Objects.requireNonNull(impasText, "impasText must not be null");

		String[] lines = impasText.split("\\r?\\n", -1);
		Map<String, Integer> symbols = new LinkedHashMap<>();
		Map<String, Integer> labels = new LinkedHashMap<>();
		List<RawInstruction> rawProgram = new ArrayList<>();
		ByteArrayOutputStream inlineBytes = new ByteArrayOutputStream();

		int pc = 0;
		for (int i = 0; i < lines.length; i++) {
			int lineNum = i + 1;
			String clean = stripComment(lines[i]).trim();
			if (clean.isEmpty()) {
				continue;
			}

			// Handle multiline .csr_inline NAME = { ... }
			Matcher csrStart = CSR_INLINE_START.matcher(clean);
			if (csrStart.matches()) {
				String symName = csrStart.group(1);
				StringBuilder body = new StringBuilder();
				String rest = csrStart.group(2);
				int closeIdx = rest.indexOf('}');
				if (closeIdx >= 0) {
					body.append(rest, 0, closeIdx);
				} else {
					body.append(rest).append('\n');
					boolean closed = false;
					while (++i < lines.length) {
						String nextClean = stripComment(lines[i]).trim();
						int cIdx = nextClean.indexOf('}');
						if (cIdx >= 0) {
							body.append(nextClean, 0, cIdx);
							closed = true;
							break;
						}
						body.append(nextClean).append('\n');
					}
					if (!closed) {
						throw new AssemblyException(lineNum, "Unterminated .csr_inline block for '" + symName + "'");
					}
				}
				int encoded = encodeCsrInline(body.toString(), inlineBytes, lineNum);
				symbols.put(symName, encoded);
				continue;
			}

			// Handle inline .array_float and .array_int / .set blocks
			Matcher floatArr = ARRAY_FLOAT_PATTERN.matcher(clean);
			if (floatArr.matches()) {
				String symName = floatArr.group(1);
				int encoded = encodeFloatArray(floatArr.group(2), inlineBytes, lineNum);
				symbols.put(symName, encoded);
				continue;
			}

			Matcher intArr = ARRAY_INT_PATTERN.matcher(clean);
			if (intArr.matches()) {
				String symName = intArr.group(1);
				int encoded = encodeIntArray(intArr.group(2), inlineBytes, lineNum);
				symbols.put(symName, encoded);
				continue;
			}

			// Handle directives starting with '.'
			if (clean.startsWith(".")) {
				parseDirective(clean, lineNum, symbols);
				continue;
			}

			// Strip optional "0xNN:" PC prefix
			String instrText = clean;
			Matcher pcMatcher = HEX_PC_PREFIX.matcher(instrText);
			if (pcMatcher.matches()) {
				instrText = pcMatcher.group(1).trim();
				if (instrText.isEmpty()) {
					continue;
				}
			}

			// Check for label definition (e.g., "loop_start:" or "loop_start: OP_NOP")
			Matcher labelMatcher = LABEL_PREFIX.matcher(instrText);
			if (labelMatcher.matches()) {
				String label = labelMatcher.group(1).trim();
				if (labels.containsKey(label)) {
					throw new AssemblyException(lineNum, "Duplicate label definition: '" + label + "'");
				}
				labels.put(label, pc);
				instrText = labelMatcher.group(2).trim();
				if (instrText.isEmpty()) {
					continue;
				}
			}

			// Split opcode and operand string
			String[] headTail = instrText.split("\\s+", 2);
			String opcodeStr = headTail[0].trim();
			String argsStr = headTail.length > 1 ? headTail[1].trim() : "";

			List<String> args = splitArgs(argsStr);
			String flagsStr = "0";
			String dstStr = "0";
			String payloadStr = "0";

			boolean isNamed = false;
			for (String arg : args) {
				int eqIdx = arg.indexOf('=');
				if (eqIdx > 0) {
					isNamed = true;
					String key = arg.substring(0, eqIdx).trim().toLowerCase(Locale.ROOT);
					String val = arg.substring(eqIdx + 1).trim();
					switch (key) {
						case "flags" -> flagsStr = val;
						case "dst" -> dstStr = val;
						case "payload" -> payloadStr = val;
						default -> throw new AssemblyException(lineNum, "Unknown named operand key '" + key + "'");
					}
				}
			}

			if (!isNamed) {
				String upperOp = opcodeStr.toUpperCase(Locale.ROOT);
				switch (args.size()) {
					case 0 -> {
						// No operands
					}
					case 1 -> {
						if ("OP_CLEAR_REG".equals(upperOp) && isRegisterToken(args.get(0))) {
							dstStr = args.get(0);
							payloadStr = "0";
						} else {
							payloadStr = args.get(0);
						}
					}
					case 2 -> {
						dstStr = args.get(0);
						payloadStr = args.get(1);
					}
					case 3 -> {
						dstStr = args.get(0);
						payloadStr = args.get(1) + " | " + args.get(2);
					}
					case 4 -> {
						dstStr = args.get(0);
						payloadStr = args.get(1) + " | " + args.get(2) + " | " + args.get(3);
					}
					case 5 -> {
						dstStr = args.get(0);
						payloadStr = args.get(1) + " | " + args.get(2) + " | " + args.get(3) + " | " + args.get(4);
					}
					default -> throw new AssemblyException(lineNum,
							"Too many operands (" + args.size() + ") in instruction: " + instrText);
				}
			}

			rawProgram.add(new RawInstruction(opcodeStr, flagsStr, dstStr, payloadStr, lineNum));
			pc++;
		}

		// Pass 2: Resolve opcodes, registers, flags, labels, and payloads
		List<InstructionWord> instructions = new ArrayList<>(rawProgram.size());
		for (int currentPc = 0; currentPc < rawProgram.size(); currentPc++) {
			RawInstruction raw = rawProgram.get(currentPc);
			byte opcode = resolveOpcode(raw.opcodeStr(), raw.lineNumber());
			byte flags = parseFlags(raw.flagsStr(), raw.lineNumber());
			int dstReg = parseRegister(raw.dstStr(), opcode, raw.lineNumber());
			int payload = parsePayload(raw.payloadStr(), opcode, currentPc, labels, symbols, raw.lineNumber());
			instructions.add(new InstructionWord(opcode, flags, (short) dstReg, payload));
		}

		return new AssembledProgram(instructions, inlineBytes.toByteArray(), labels, symbols);
	}

	/**
	 * Resolves an opcode mnemonic (including aliases and {@code OP_RESERVED_XX}) to
	 * its 8-bit opcode byte, or throws {@link AssemblyException}.
	 */
	public static byte resolveOpcode(String mnemonic, int lineNumber) {
		if (mnemonic == null || mnemonic.isEmpty()) {
			throw new AssemblyException(lineNumber, "Empty opcode mnemonic");
		}
		String upper = mnemonic.trim().toUpperCase(Locale.ROOT);
		Byte found = OPCODE_TABLE.get(upper);
		if (found != null) {
			return found;
		}
		if (upper.startsWith("OP_RESERVED_")) {
			String hexPart = upper.substring("OP_RESERVED_".length());
			try {
				int parsed = Integer.parseInt(hexPart, 16);
				if (parsed >= 0 && parsed <= 0xFF) {
					return (byte) parsed;
				}
			} catch (NumberFormatException ignored) {
			}
		}
		throw new AssemblyException(lineNumber, "Unknown opcode: '" + mnemonic + "'");
	}

	/**
	 * Returns an unmodifiable view of all recognized opcode mnemonics and aliases.
	 */
	public static Map<String, Byte> opcodeTable() {
		return OPCODE_TABLE;
	}

	private static void parseDirective(String line, int lineNum, Map<String, Integer> symbols) {
		String[] tokens = line.split("\\s+");
		String directive = tokens[0].toLowerCase(Locale.ROOT);

		switch (directive) {
			case ".text", ".data", ".version", ".instructions", ".fuel", ".max_dop" -> {
				return;
			}
			case ".domain", ".relation", ".rel", ".attribute", ".const", ".equ" -> {
				parseSymbolDirective(directive, line, lineNum, symbols);
				return;
			}
			default -> throw new AssemblyException(lineNum, "Unknown directive: '" + tokens[0] + "'");
		}
	}

	private static void parseSymbolDirective(String directive, String line, int lineNum, Map<String, Integer> symbols) {
		String body = line.substring(directive.length()).trim();
		if (body.isEmpty()) {
			throw new AssemblyException(lineNum, "Directive " + directive + " requires a symbol name and value");
		}

		String symName;
		String valPart;
		int eqIdx = body.indexOf('=');
		if (eqIdx >= 0) {
			symName = body.substring(0, eqIdx).trim().replace("\"", "");
			valPart = body.substring(eqIdx + 1).trim();
		} else {
			String[] parts = body.split("[\\s,]+", 2);
			if (parts.length < 2) {
				throw new AssemblyException(lineNum, "Malformed " + directive + " directive: " + line);
			}
			symName = parts[0].trim().replace("\"", "");
			valPart = parts[1].trim();
		}

		// Strip trailing metadata like ", multiplicity = many_to_one"
		int commaIdx = valPart.indexOf(',');
		if (commaIdx >= 0) {
			valPart = valPart.substring(0, commaIdx).trim();
		}

		if (symName.isEmpty() || valPart.isEmpty()) {
			throw new AssemblyException(lineNum, "Malformed " + directive + " directive: " + line);
		}

		int value = parseIntLiteral(valPart, lineNum);
		switch (directive) {
			case ".domain" -> {
				symbols.put("DOMAIN_" + symName.toUpperCase(Locale.ROOT), value);
				symbols.put(symName, value);
			}
			case ".relation", ".rel" -> {
				symbols.put("REL_" + symName.toUpperCase(Locale.ROOT), value);
				symbols.put(symName, value);
			}
			case ".attribute" -> {
				symbols.put("ATTR_" + symName.toUpperCase(Locale.ROOT), value);
				symbols.put(symName, value);
			}
			case ".const", ".equ" -> symbols.put(symName, value);
			default -> throw new AssemblyException(lineNum, "Unknown symbol directive: " + directive);
		}
	}

	private static int encodeCsrInline(String csrBody, ByteArrayOutputStream inlineBytes, int lineNum) {
		align4(inlineBytes);
		int offsetStart = inlineBytes.size();
		List<Integer> offsets = new ArrayList<>();
		offsets.add(0);
		List<Integer> targets = new ArrayList<>();
		int nodeCount = 0;

		for (String rawRow : csrBody.split("\\r?\\n")) {
			String trimmed = rawRow.trim();
			if (trimmed.isEmpty() || !trimmed.contains(":")) {
				continue;
			}
			String[] parts = trimmed.split(":", 2);
			String tgtStr = parts[1].trim().replace("[", "").replace("]", "").trim();
			if (!tgtStr.isEmpty()) {
				for (String item : tgtStr.split(",")) {
					String t = item.trim();
					if (!t.isEmpty()) {
						targets.add(parseIntLiteral(t, lineNum));
					}
				}
			}
			offsets.add(targets.size());
			nodeCount++;
		}

		int maxTgt = -1;
		for (int tgt : targets) {
			if (tgt > maxTgt) {
				maxTgt = tgt;
			}
		}
		int totalNodes = Math.max(nodeCount, maxTgt + 1);
		while (offsets.size() <= totalNodes) {
			offsets.add(targets.size());
		}

		ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		for (int off : offsets) {
			buf.clear();
			buf.putInt(off);
			inlineBytes.writeBytes(buf.array());
		}
		for (int tgt : targets) {
			buf.clear();
			buf.putInt(tgt);
			inlineBytes.writeBytes(buf.array());
		}

		return (offsetStart & 0xFFFF) | ((totalNodes & 0xFFFF) << 16);
	}

	private static int encodeFloatArray(String arrBody, ByteArrayOutputStream inlineBytes, int lineNum) {
		align4(inlineBytes);
		int offsetStart = inlineBytes.size();
		List<Float> floats = new ArrayList<>();
		if (!arrBody.trim().isEmpty()) {
			for (String item : arrBody.split(",")) {
				String t = item.trim();
				if (t.isEmpty()) {
					continue;
				}
				floats.add(parseFloatLiteral(t, lineNum));
			}
		}
		ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		for (float f : floats) {
			buf.clear();
			buf.putFloat(f);
			inlineBytes.writeBytes(buf.array());
		}
		return (offsetStart & 0xFFFF) | ((floats.size() & 0xFFFF) << 16);
	}

	private static int encodeIntArray(String arrBody, ByteArrayOutputStream inlineBytes, int lineNum) {
		align4(inlineBytes);
		int offsetStart = inlineBytes.size();
		List<Integer> ints = new ArrayList<>();
		if (!arrBody.trim().isEmpty()) {
			for (String item : arrBody.split(",")) {
				String t = item.trim();
				if (t.isEmpty()) {
					continue;
				}
				ints.add(parseIntLiteral(t, lineNum));
			}
		}
		ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		for (int v : ints) {
			buf.clear();
			buf.putInt(v);
			inlineBytes.writeBytes(buf.array());
		}
		return (offsetStart & 0xFFFF) | ((ints.size() & 0xFFFF) << 16);
	}

	private static void align4(ByteArrayOutputStream out) {
		while ((out.size() & 3) != 0) {
			out.write(0);
		}
	}

	private static String stripComment(String line) {
		int semi = line.indexOf(';');
		int hash = line.indexOf('#');
		int cut = -1;
		if (semi >= 0 && hash >= 0) {
			cut = Math.min(semi, hash);
		} else if (semi >= 0) {
			cut = semi;
		} else if (hash >= 0) {
			cut = hash;
		}
		return cut >= 0 ? line.substring(0, cut) : line;
	}

	private static List<String> splitArgs(String argsStr) {
		if (argsStr == null || argsStr.isEmpty()) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (String part : argsStr.split(",")) {
			String trimmed = part.trim();
			if (!trimmed.isEmpty()) {
				out.add(trimmed);
			}
		}
		return out;
	}

	private static boolean isRegisterToken(String s) {
		String trimmed = s.trim();
		if (trimmed.length() >= 2 && (trimmed.charAt(0) == 'R' || trimmed.charAt(0) == 'r')) {
			for (int i = 1; i < trimmed.length(); i++) {
				if (!Character.isDigit(trimmed.charAt(i))) {
					return false;
				}
			}
			return true;
		}
		return false;
	}

	private static int parseRegister(String s, byte opcode, int lineNum) {
		String trimmed = s == null ? "" : s.trim();
		if (trimmed.isEmpty()) {
			return 0;
		}
		int reg;
		if (isRegisterToken(trimmed)) {
			try {
				reg = Integer.parseInt(trimmed.substring(1));
			} catch (NumberFormatException e) {
				throw new AssemblyException(lineNum, "Invalid register identifier: '" + s + "'", e);
			}
		} else {
			try {
				reg = Integer.parseInt(trimmed);
			} catch (NumberFormatException e) {
				throw new AssemblyException(lineNum, "Invalid register identifier: '" + s + "'", e);
			}
		}
		int opUnsigned = Byte.toUnsignedInt(opcode);
		boolean allowRawDst = (opUnsigned == 0x09 || opUnsigned == 0x0B || opUnsigned == 0x0C);
		if (reg < 0 || (!allowRawDst && reg >= 64) || reg > 0xFFFF) {
			throw new AssemblyException(lineNum,
					"Register out of range (expected R0..R63): '" + s + "' (resolved " + reg + ")");
		}
		return reg;
	}

	private static byte parseFlags(String s, int lineNum) {
		String trimmed = s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
		if (trimmed.isEmpty() || "0".equals(trimmed)) {
			return 0;
		}
		int val = 0;
		for (String part : trimmed.split("\\|")) {
			String p = part.trim();
			if (p.isEmpty()) {
				continue;
			}
			switch (p) {
				case "FLAG_MODE_BITSET", "OP_FLAG_MODE_BITSET", "BITSET" -> val |= 0x01;
				case "FLAG_ACCUMULATE", "OP_FLAG_ACCUMULATE", "ACCUMULATE" -> val |= 0x02;
				case "FLAG_INVERT", "OP_FLAG_INVERT", "INVERT" -> val |= 0x04;
				case "FLAG_OFFHEAP", "OP_FLAG_OFFHEAP", "OFFHEAP" -> val |= 0x08;
				case "FLAG_EXTENDED", "OP_FLAG_EXTENDED", "EXTENDED" -> val |= 0x80;
				default -> {
					int parsed;
					if (p.startsWith("0X")) {
						try {
							parsed = Integer.parseInt(p.substring(2), 16);
						} catch (NumberFormatException e) {
							throw new AssemblyException(lineNum, "Invalid hex flag value: '" + p + "'", e);
						}
					} else {
						try {
							parsed = Integer.parseInt(p);
						} catch (NumberFormatException e) {
							throw new AssemblyException(lineNum, "Invalid flag value: '" + p + "'", e);
						}
					}
					if (parsed < 0 || parsed > 0xFF) {
						throw new AssemblyException(lineNum, "Flag value out of 8-bit range (0..255): '" + p + "'");
					}
					val |= parsed;
				}
			}
		}
		return (byte) val;
	}

	private static int parsePayload(String payloadStr, byte opcode, int currentPc, Map<String, Integer> labels,
			Map<String, Integer> symbols, int lineNum) {
		String[] rawParts = payloadStr.split("\\|");
		List<String> parts = new ArrayList<>(rawParts.length);
		for (String p : rawParts) {
			parts.add(p.trim());
		}

		// Special OP_CSC_WALK (0x18) packing matching assemble.rs
		if (opcode == VmRegisterType.OP_CSC_WALK) {
			if (parts.size() == 2) {
				int src = parseRegister(parts.get(0), opcode, lineNum);
				int rel = resolveSingleVal(parts.get(1), opcode, symbols, lineNum);
				return (src & 0xFFFF) | (63 << 16) | ((rel & 0xFF) << 24);
			} else if (parts.size() == 3) {
				int src = parseRegister(parts.get(0), opcode, lineNum);
				int unv = parseRegister(parts.get(1), opcode, lineNum);
				int rel = resolveSingleVal(parts.get(2), opcode, symbols, lineNum);
				return (src & 0xFFFF) | ((unv & 0xFF) << 16) | ((rel & 0xFF) << 24);
			}
		}

		if (parts.size() == 1) {
			String token = parts.get(0);
			Integer targetPc = labels.get(token);
			if (targetPc != null) {
				return targetPc - currentPc;
			}
			if (opcode == VmRegisterType.OP_LOAD_CONST_FLOAT && isFloatLiteral(token)) {
				return Float.floatToRawIntBits(parseFloatLiteral(token, lineNum));
			}
			return resolveSingleVal(token, opcode, symbols, lineNum);
		}

		if (parts.size() == 4) {
			int b0 = resolveSingleVal(parts.get(0), opcode, symbols, lineNum) & 0xFF;
			int b1 = resolveSingleVal(parts.get(1), opcode, symbols, lineNum) & 0xFF;
			int b2 = resolveSingleVal(parts.get(2), opcode, symbols, lineNum) & 0xFF;
			int b3 = resolveSingleVal(parts.get(3), opcode, symbols, lineNum) & 0xFF;
			return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
		}

		if (parts.size() == 3) {
			int b0 = resolveSingleVal(parts.get(0), opcode, symbols, lineNum) & 0xFF;
			int b1 = resolveSingleVal(parts.get(1), opcode, symbols, lineNum) & 0xFF;
			int hi16 = resolveSingleVal(parts.get(2), opcode, symbols, lineNum) & 0xFFFF;
			return b0 | (b1 << 8) | (hi16 << 16);
		}

		if (parts.size() == 2) {
			int lo16 = resolveSingleVal(parts.get(0), opcode, symbols, lineNum) & 0xFFFF;
			int hi16 = resolveSingleVal(parts.get(1), opcode, symbols, lineNum) & 0xFFFF;
			return lo16 | (hi16 << 16);
		}

		throw new AssemblyException(lineNum,
				"Unsupported pipe-packed payload arity (" + parts.size() + "): " + payloadStr);
	}

	private static int resolveSingleVal(String token, byte opcode, Map<String, Integer> symbols, int lineNum) {
		String trimmed = token.trim();
		if (trimmed.isEmpty()) {
			return 0;
		}
		if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
			try {
				long unsigned = Long.parseUnsignedLong(trimmed.substring(2), 16);
				if (unsigned > 0xFFFFFFFFL) {
					throw new AssemblyException(lineNum, "Hex operand exceeds 32-bit range: '" + trimmed + "'");
				}
				return (int) unsigned;
			} catch (NumberFormatException e) {
				throw new AssemblyException(lineNum, "Invalid hex operand: '" + trimmed + "'", e);
			}
		}
		if (isRegisterToken(trimmed)) {
			return parseRegister(trimmed, opcode, lineNum);
		}
		Integer symVal = symbols.get(trimmed);
		if (symVal != null) {
			return symVal;
		}
		String upper = trimmed.toUpperCase(Locale.ROOT);
		Integer constVal = CONSTANT_TABLE.get(upper);
		if (constVal != null) {
			return constVal;
		}
		if (upper.startsWith("REL_") || upper.startsWith("DOMAIN_") || upper.startsWith("ATTR_")) {
			String suffix = upper.substring(upper.indexOf('_') + 1);
			if (!suffix.isEmpty() && isAllDigits(suffix)) {
				return parseUnsigned32(suffix, lineNum);
			}
		}
		if (trimmed.startsWith("+") || trimmed.startsWith("-")) {
			try {
				return Integer.parseInt(trimmed);
			} catch (NumberFormatException e) {
				throw new AssemblyException(lineNum, "Invalid signed integer operand: '" + trimmed + "'", e);
			}
		}
		if (isAllDigits(trimmed)) {
			return parseUnsigned32(trimmed, lineNum);
		}
		throw new AssemblyException(lineNum, "Unresolved symbol, label, or malformed operand: '" + trimmed + "'");
	}

	private static boolean isAllDigits(String s) {
		for (int i = 0; i < s.length(); i++) {
			if (!Character.isDigit(s.charAt(i))) {
				return false;
			}
		}
		return !s.isEmpty();
	}

	private static int parseUnsigned32(String s, int lineNum) {
		try {
			long val = Long.parseUnsignedLong(s);
			if (val > 0xFFFFFFFFL) {
				throw new AssemblyException(lineNum, "Integer operand exceeds 32-bit unsigned range: '" + s + "'");
			}
			return (int) val;
		} catch (NumberFormatException e) {
			throw new AssemblyException(lineNum, "Invalid integer operand: '" + s + "'", e);
		}
	}

	private static int parseIntLiteral(String s, int lineNum) {
		String trimmed = s.trim();
		if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
			try {
				long val = Long.parseUnsignedLong(trimmed.substring(2), 16);
				if (val > 0xFFFFFFFFL) {
					throw new AssemblyException(lineNum, "Hex literal exceeds 32-bit range: '" + trimmed + "'");
				}
				return (int) val;
			} catch (NumberFormatException e) {
				throw new AssemblyException(lineNum, "Invalid hex literal: '" + trimmed + "'", e);
			}
		}
		try {
			if (trimmed.startsWith("-") || trimmed.startsWith("+")) {
				return Integer.parseInt(trimmed);
			}
			long val = Long.parseUnsignedLong(trimmed);
			if (val > 0xFFFFFFFFL) {
				throw new AssemblyException(lineNum, "Integer literal exceeds 32-bit range: '" + trimmed + "'");
			}
			return (int) val;
		} catch (NumberFormatException e) {
			throw new AssemblyException(lineNum, "Invalid integer literal: '" + trimmed + "'", e);
		}
	}

	private static boolean isFloatLiteral(String s) {
		String t = s.trim();
		if (t.equalsIgnoreCase("nan") || t.equalsIgnoreCase("inf") || t.equalsIgnoreCase("+inf")
				|| t.equalsIgnoreCase("-inf") || t.equalsIgnoreCase("infinity") || t.equalsIgnoreCase("+infinity")
				|| t.equalsIgnoreCase("-infinity")) {
			return true;
		}
		if (t.endsWith("f") || t.endsWith("F")) {
			t = t.substring(0, t.length() - 1);
		}
		return t.contains(".") || t.contains("e") || t.contains("E");
	}

	private static float parseFloatLiteral(String s, int lineNum) {
		String t = s.trim();
		if (t.equalsIgnoreCase("nan")) {
			return Float.NaN;
		}
		if (t.equalsIgnoreCase("inf") || t.equalsIgnoreCase("+inf") || t.equalsIgnoreCase("infinity")
				|| t.equalsIgnoreCase("+infinity")) {
			return Float.POSITIVE_INFINITY;
		}
		if (t.equalsIgnoreCase("-inf") || t.equalsIgnoreCase("-infinity")) {
			return Float.NEGATIVE_INFINITY;
		}
		if (t.endsWith("f") || t.endsWith("F")) {
			t = t.substring(0, t.length() - 1);
		}
		try {
			return Float.parseFloat(t);
		} catch (NumberFormatException e) {
			throw new AssemblyException(lineNum, "Invalid float literal: '" + s + "'", e);
		}
	}

	private static Map<String, Byte> buildOpcodeTable() {
		Map<String, Byte> map = new HashMap<>();
		for (Field field : VmRegisterType.class.getDeclaredFields()) {
			String name = field.getName();
			if (name.startsWith("OP_") && !name.startsWith("OP_FLAG_") && !name.equals("OP_EXTENSION_PAYLOAD")
					&& field.getType() == byte.class) {
				try {
					map.put(name, field.getByte(null));
				} catch (IllegalAccessException ignored) {
				}
			}
		}

		// Canonical names & aliases aligned with assemble.rs and spec test vectors
		map.put("OP_LOAD_INLINE_INT_ARRAY", (byte) 0x08);
		map.put("OP_LOAD_INLINE_SET", (byte) 0x0A);
		map.put("OP_LOAD_INLINE_SET_DENSE", (byte) 0x0A);
		map.put("OP_LOAD_INLINE_SET_ROARING", (byte) 0x0A);
		map.put("OP_LOAD_INLINE_NODE_ARRAY", (byte) 0x0A);
		map.put("OP_INIT_MOCK_NODE_ATTR", (byte) 0x0B);
		map.put("OP_INIT_MOCK_EDGE_ATTR", (byte) 0x0C);
		map.put("OP_ADAPTIVE_WALK", (byte) 0x1D);
		map.put("OP_DENSE_WALK_LEGACY", (byte) 0x1D);
		map.put("OP_VECTOR_MUL_ATTR", (byte) 0x34);
		map.put("OP_COALESCE", (byte) 0x3B);
		map.put("OP_EXTRACT_VALIDITY", (byte) 0x3C);
		map.put("OP_MOVE", (byte) 0x70);
		map.put("OP_COALESCE_BITSET", (byte) 0x94);
		map.put("OP_COALESCE_DENSE_ARRAY", (byte) 0x95);

		return Collections.unmodifiableMap(map);
	}

	private static Map<String, Integer> buildConstantTable() {
		Map<String, Integer> map = new HashMap<>();
		map.put("SEMIRING_PLUS_TIMES", VmRegisterType.SEMIRING_PLUS_TIMES);
		map.put("SEMIRING_MIN_PLUS", VmRegisterType.SEMIRING_MIN_PLUS);
		map.put("SEMIRING_MAX_MIN", VmRegisterType.SEMIRING_MAX_MIN);
		map.put("SEMIRING_BOOL", VmRegisterType.SEMIRING_BOOL);
		map.put("BINARY_OP_ADD", VmRegisterType.BINARY_OP_ADD);
		map.put("BINARY_OP_MUL", VmRegisterType.BINARY_OP_MUL);
		map.put("BINARY_OP_MIN", VmRegisterType.BINARY_OP_MIN);
		map.put("BINARY_OP_MAX", VmRegisterType.BINARY_OP_MAX);
		map.put("BINARY_OP_AND", VmRegisterType.BINARY_OP_AND);
		map.put("BINARY_OP_OR", VmRegisterType.BINARY_OP_OR);
		map.put("BINARY_OP_ARGMIN", VmRegisterType.BINARY_OP_ARGMIN);
		map.put("BINARY_OP_ARGMAX", VmRegisterType.BINARY_OP_ARGMAX);
		return Collections.unmodifiableMap(map);
	}
}
