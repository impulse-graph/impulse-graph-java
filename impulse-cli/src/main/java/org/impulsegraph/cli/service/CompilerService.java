package org.impulsegraph.cli.service;

import org.impulsegraph.api.schema.GraphManifest;
import org.impulsegraph.api.schema.SchemaYamlCodec;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.parser.ImpScmParser;
import org.impulsegraph.compiler.ast.parser.ImpScmSerializer;
import org.impulsegraph.compiler.cypher.CypherCompiler;
import org.impulsegraph.compiler.datalog.ImpLogCompiler;
import org.impulsegraph.compiler.emitter.ImpAsmAssembler;
import org.impulsegraph.compiler.emitter.ImpAsmDisassembler;
import org.impulsegraph.compiler.emitter.ImpOpsBinaryCodec;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.compiler.explain.QueryExplainer;
import org.impulsegraph.compiler.impk.ImpKCompiler;
import org.impulsegraph.compiler.passes.stage1.SchemaBindValidatorPass;
import org.impulsegraph.compiler.passes.stage1.SchemaView;
import org.impulsegraph.compiler.registry.QueryObject;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Reusable compiler service covering frontend lowering (Cypher, ImpK, ImpLog,
 * ImpScheme), schema binding validation, bytecode emission, ImpAsm assembly /
 * disassembly, and EXPLAIN plan generation.
 */
public final class CompilerService {

	public enum QueryLanguage {
		CYPHER, IMPK, IMPLOG, IMPSCM;

		public static QueryLanguage parse(String raw) {
			if (raw == null || raw.isBlank()) {
				return null;
			}
			return switch (raw.trim().toLowerCase(Locale.ROOT)) {
				case "cypher", "opencypher" -> CYPHER;
				case "impk" -> IMPK;
				case "implog", "datalog" -> IMPLOG;
				case "impscm", "scheme", "scm" -> IMPSCM;
				default -> throw new IllegalArgumentException(
						"Unsupported query language: " + raw + " (expected cypher|impk|implog|impscm)");
			};
		}

		public static QueryLanguage detectFromPath(Path path) {
			String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
			if (name.endsWith(".cypher") || name.endsWith(".cyp")) {
				return CYPHER;
			}
			if (name.endsWith(".impk")) {
				return IMPK;
			}
			if (name.endsWith(".implog") || name.endsWith(".dl")) {
				return IMPLOG;
			}
			if (name.endsWith(".impscm") || name.endsWith(".scm")) {
				return IMPSCM;
			}
			throw new IllegalArgumentException("Cannot infer query language from file extension: " + name);
		}
	}

	public record CompileResult(QueryLanguage language, ImpScmNode ast, String textOutput, byte[] binaryOutput,
			Path writtenPath) {
	}

	private CompilerService() {
	}

	public static ImpScmNode lowerToAst(String source, QueryLanguage language) {
		Objects.requireNonNull(source, "source must not be null");
		Objects.requireNonNull(language, "language must not be null");
		return switch (language) {
			case CYPHER -> CypherCompiler.compile(source).ast();
			case IMPK -> ImpKCompiler.compile(source);
			case IMPLOG -> ImpLogCompiler.compile(source);
			case IMPSCM -> {
				ImpScmNode parsed = ImpScmParser.parse(source);
				if (parsed == null) {
					throw new IllegalArgumentException("Empty or invalid ImpScheme source");
				}
				yield parsed;
			}
		};
	}

	public static void validateAgainstSchema(ImpScmNode ast, Path schemaPath) throws IOException {
		Objects.requireNonNull(ast, "ast must not be null");
		Objects.requireNonNull(schemaPath, "schemaPath must not be null");
		GraphManifest manifest = SchemaYamlCodec.parse(schemaPath);
		SchemaView view = SchemaView.fromGraphManifest(manifest);
		SchemaBindValidatorPass.validate(ast, view);
	}

	public static CompileResult compile(Path queryFile, QueryLanguage langOverride, Path optionalSchema,
			Path optionalSnapshot, boolean emitIr, Path optionalOutput) throws IOException {
		Objects.requireNonNull(queryFile, "queryFile must not be null");
		QueryLanguage lang = langOverride != null ? langOverride : QueryLanguage.detectFromPath(queryFile);
		String source = Files.readString(queryFile);
		ImpScmNode ast = lowerToAst(source, lang);

		if (optionalSchema != null) {
			validateAgainstSchema(ast, optionalSchema);
		}

		boolean wantsBinary = optionalOutput != null
				&& optionalOutput.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".impb");

		if (emitIr || optionalSnapshot == null) {
			if (wantsBinary && optionalSnapshot == null) {
				throw new IllegalArgumentException("Emitting .impb bytecode requires --snapshot <file.imps>");
			}
			String ir = ImpScmSerializer.serialize(ast);
			if (optionalOutput != null) {
				writeStringFile(optionalOutput, ir + "\n");
			}
			return new CompileResult(lang, ast, ir, null, optionalOutput);
		}

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(optionalSnapshot, arena,
					false);
			ImpOpsBytecodeEmitter.EmittedProgram emitted = ImpOpsBytecodeEmitter.emit(ast, loaded.graph(), arena);
			if (wantsBinary) {
				byte[] bytes = ImpOpsBinaryCodec.encode(emitted.instructionList());
				ImpOpsBinaryCodec.writeTo(emitted.instructionList(), optionalOutput);
				return new CompileResult(lang, ast, null, bytes, optionalOutput);
			}
			String asm = ImpAsmDisassembler.disassemble(emitted);
			if (optionalOutput != null) {
				writeStringFile(optionalOutput, asm);
			}
			return new CompileResult(lang, ast, asm, null, optionalOutput);
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException(
					"Failed to compile query against snapshot " + optionalSnapshot + ": " + e.getMessage(), e);
		}
	}

	public static int assemble(Path inputImpas, Path outputImpb) throws IOException {
		Objects.requireNonNull(inputImpas, "inputImpas must not be null");
		Objects.requireNonNull(outputImpb, "outputImpb must not be null");
		ImpAsmAssembler.AssembledProgram assembled = ImpAsmAssembler.assemble(inputImpas);
		ImpOpsBinaryCodec.writeTo(assembled.instructions(), outputImpb);
		return assembled.instructions().size();
	}

	public static String disassemble(Path inputImpb) throws IOException {
		Objects.requireNonNull(inputImpb, "inputImpb must not be null");
		List<ImpOpsBytecodeEmitter.InstructionWord> words = ImpOpsBinaryCodec.readFrom(inputImpb);
		return ImpAsmDisassembler.disassemble(words);
	}

	public static String explain(String queryFileOrString, QueryLanguage langOverride, Path optionalSnapshot,
			Path optionalSchema) throws IOException {
		Objects.requireNonNull(queryFileOrString, "query must not be null");
		String queryName = "inline-query";
		String source = queryFileOrString;
		QueryLanguage lang = langOverride;

		Path maybePath = Path.of(queryFileOrString);
		if (Files.exists(maybePath) && Files.isRegularFile(maybePath)) {
			queryName = maybePath.getFileName().toString();
			source = Files.readString(maybePath);
			if (lang == null) {
				lang = QueryLanguage.detectFromPath(maybePath);
			}
		} else if (lang == null) {
			lang = inferLanguageFromSource(source);
		}

		ImpScmNode ast = lowerToAst(source, lang);
		if (optionalSchema != null) {
			validateAgainstSchema(ast, optionalSchema);
		}

		QueryObject qo = new QueryObject(queryName, source, ast);
		if (optionalSnapshot == null) {
			return QueryExplainer.explain(qo, null);
		}

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(optionalSnapshot, arena,
					false);
			return QueryExplainer.explain(qo, loaded.graph());
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException(
					"Failed to explain query against snapshot " + optionalSnapshot + ": " + e.getMessage(), e);
		}
	}

	private static QueryLanguage inferLanguageFromSource(String source) {
		String trimmed = source.trim();
		if (trimmed.startsWith("(")) {
			return QueryLanguage.IMPSCM;
		}
		if (trimmed.toUpperCase(Locale.ROOT).startsWith("MATCH")) {
			return QueryLanguage.CYPHER;
		}
		if (trimmed.contains(":-")) {
			return QueryLanguage.IMPLOG;
		}
		return QueryLanguage.IMPK;
	}

	private static void writeStringFile(Path path, String content) throws IOException {
		Path parent = path.toAbsolutePath().normalize().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.writeString(path, content);
	}
}
