package org.impulsegraph.cli.service;

import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.emitter.ImpAsmAssembler;
import org.impulsegraph.compiler.emitter.ImpOpsBinaryCodec;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.vm.ImpulseVmInterpreter;
import org.impulsegraph.vm.ImpulseVmValidator;
import org.impulsegraph.vm.VmHandlers;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Reusable execution service for running {@code .impb}, {@code .impas},
 * {@code .impscm}, {@code .cypher}, {@code .impk}, and {@code .implog} programs
 * against an {@code .imps} snapshot on {@link ImpulseVmInterpreter}.
 */
public final class ExecutionService {

	private ExecutionService() {
	}

	public record ExecutionReport(Path snapshotPath, Path programPath, int instructionCount, boolean verified,
			long elapsedMicros, String resultType, String summary, long cardinality, List<Long> sampleNodeIds,
			Object scalarValue) {

		public String toText() {
			StringBuilder sb = new StringBuilder();
			sb.append("Impulse VM Execution Result\n");
			sb.append("  Snapshot     : ").append(snapshotPath).append('\n');
			sb.append("  Program      : ").append(programPath).append('\n');
			sb.append("  Instructions : ").append(instructionCount).append('\n');
			sb.append("  Verified     : ").append(verified).append('\n');
			sb.append("  Elapsed (us) : ").append(elapsedMicros).append('\n');
			sb.append("  Result Type  : ").append(resultType).append('\n');
			sb.append("  Summary      : ").append(summary).append('\n');
			if (scalarValue != null) {
				sb.append("  Scalar Return Value: ").append(scalarValue).append('\n');
			}
			if (!sampleNodeIds.isEmpty()) {
				sb.append("  Node IDs     : ").append(sampleNodeIds).append('\n');
			}
			return sb.toString();
		}

		public String toJson() {
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("snapshot", snapshotPath.toString());
			root.put("program", programPath.toString());
			root.put("instructionCount", instructionCount);
			root.put("verified", verified);
			root.put("elapsedMicros", elapsedMicros);
			root.put("resultType", resultType);
			root.put("summary", summary);
			root.put("cardinality", cardinality);
			root.put("sampleNodeIds", sampleNodeIds);
			if (scalarValue != null) {
				root.put("scalarValue", scalarValue);
			}
			return JsonWriter.toJson(root);
		}
	}

	public static ExecutionReport run(Path snapshotPath, Path programPath, long inputVal, boolean verify)
			throws IOException {
		Objects.requireNonNull(snapshotPath, "snapshotPath must not be null");
		Objects.requireNonNull(programPath, "programPath must not be null");

		String lower = programPath.getFileName().toString().toLowerCase(Locale.ROOT);

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena, false);
			MemorySegment programSeg;
			long instructionCount;
			List<String> stringPool = null;
			MemorySegment inlineDataSeg = null;
			VmHandlers.Instruction[] vmInstructions;

			if (lower.endsWith(".impb")) {
				List<ImpOpsBytecodeEmitter.InstructionWord> words = ImpOpsBinaryCodec.readFrom(programPath);
				programSeg = ImpOpsBinaryCodec.toMemorySegment(words, arena);
				instructionCount = words.size();
				vmInstructions = ImpOpsBinaryCodec.toVmInstructions(words);
			} else if (lower.endsWith(".impas")) {
				ImpAsmAssembler.AssembledProgram assembled = ImpAsmAssembler.assemble(programPath);
				List<ImpOpsBytecodeEmitter.InstructionWord> words = assembled.instructions();
				programSeg = ImpOpsBinaryCodec.toMemorySegment(words, arena);
				instructionCount = words.size();
				vmInstructions = ImpOpsBinaryCodec.toVmInstructions(words);
				byte[] inlineBytes = assembled.inlineData();
				if (inlineBytes.length > 0) {
					inlineDataSeg = arena.allocate(inlineBytes.length, 8);
					MemorySegment.copy(inlineBytes, 0, inlineDataSeg, ValueLayout.JAVA_BYTE, 0, inlineBytes.length);
				}
			} else {
				CompilerService.QueryLanguage lang = CompilerService.QueryLanguage.detectFromPath(programPath);
				ImpScmNode ast = CompilerService.lowerToAst(Files.readString(programPath), lang);
				ImpOpsBytecodeEmitter.EmittedProgram emitted = ImpOpsBytecodeEmitter.emit(ast, loaded.graph(), arena);
				programSeg = emitted.programSegment();
				instructionCount = emitted.instructionCount();
				stringPool = emitted.stringPool();
				inlineDataSeg = emitted.inlineDataSegment();
				vmInstructions = ImpOpsBinaryCodec.toVmInstructions(emitted.instructionList());
			}

			if (verify) {
				String validationStatus = ImpulseVmValidator.validate(vmInstructions);
				if (validationStatus != null && !"IMPULSE_VM_OK".equals(validationStatus)) {
					throw new IllegalStateException("Bytecode verification failed: " + validationStatus);
				}
			}

			long startNs = System.nanoTime();
			Object result = ImpulseVmInterpreter.execute(programSeg, vmInstructions, instructionCount, loaded.graph(),
					inputVal, arena, stringPool, inlineDataSeg);
			long elapsedMicros = (System.nanoTime() - startNs) / 1_000L;

			return summarizeResult(snapshotPath, programPath, (int) instructionCount, verify, elapsedMicros, result);
		} catch (IOException e) {
			throw e;
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("VM execution failed: " + e.getMessage(), e);
		}
	}

	private static ExecutionReport summarizeResult(Path snapshotPath, Path programPath, int instructionCount,
			boolean verified, long elapsedMicros, Object result) {
		if (result == null) {
			return new ExecutionReport(snapshotPath, programPath, instructionCount, verified, elapsedMicros, "null",
					"null", 0L, List.of(), null);
		}
		if (result instanceof ImpulseBitSet bs) {
			long card = bs.cardinality();
			List<Long> samples = new ArrayList<>();
			for (int i = bs.nextSetBit(0); i >= 0 && samples.size() < 32; i = bs.nextSetBit(i + 1)) {
				samples.add((long) i);
			}
			return new ExecutionReport(snapshotPath, programPath, instructionCount, verified, elapsedMicros,
					"ImpulseBitSet", "cardinality=" + card, card, List.copyOf(samples), null);
		}
		if (result instanceof Long || result instanceof Integer || result instanceof Short || result instanceof Byte) {
			long val = ((Number) result).longValue();
			return new ExecutionReport(snapshotPath, programPath, instructionCount, verified, elapsedMicros,
					"SCALAR_INT", String.valueOf(val), 0L, List.of(), val);
		}
		if (result instanceof Float f) {
			return new ExecutionReport(snapshotPath, programPath, instructionCount, verified, elapsedMicros,
					"SCALAR_FLOAT", String.valueOf(f), 0L, List.of(), f);
		}
		if (result instanceof Double d) {
			return new ExecutionReport(snapshotPath, programPath, instructionCount, verified, elapsedMicros,
					"SCALAR_DOUBLE", String.valueOf(d), 0L, List.of(), d);
		}
		return new ExecutionReport(snapshotPath, programPath, instructionCount, verified, elapsedMicros,
				result.getClass().getSimpleName(), String.valueOf(result), 0L, List.of(), null);
	}
}
