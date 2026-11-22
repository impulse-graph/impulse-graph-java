package org.impulsegraph.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.impulsegraph.cli.service.CompilerService;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.parser.ImpScmSerializer;
import org.impulsegraph.compiler.passes.stage1.SchemaValidationException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Ahead-of-Time (AOT) query compilation and build-time schema validation Mojo.
 * Scans {@code queriesDirectory} for {@code .cypher}, {@code .impk},
 * {@code .implog}, and {@code .impscm} files, validates each against
 * {@code schemaFile} using {@code SchemaBindValidatorPass}, and writes
 * normalized {@code .impscm} IR files to {@code outputDirectory}.
 */
@Mojo(name = "aot-compile", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class AotCompileQueriesMojo extends AbstractMojo {

	@Parameter(property = "impulse.queriesDirectory", defaultValue = "${project.basedir}/src/main/impulse/queries")
	File queriesDirectory;

	@Parameter(property = "impulse.schemaFile", defaultValue = "${project.basedir}/src/main/resources/schema.yaml")
	File schemaFile;

	@Parameter(property = "impulse.aotOutputDirectory", defaultValue = "${project.build.directory}/generated-resources/impulse/queries")
	File outputDirectory;

	@Parameter(property = "impulse.skipIfMissing", defaultValue = "false")
	boolean skipIfMissing;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (queriesDirectory == null || !queriesDirectory.exists()) {
			String location = queriesDirectory != null ? queriesDirectory.getAbsolutePath() : "<null>";
			if (skipIfMissing) {
				getLog().info("Skipping impulse:aot-compile because queriesDirectory does not exist: " + location);
				return;
			}
			throw new MojoExecutionException("queriesDirectory does not exist: " + location);
		}
		if (schemaFile == null || !schemaFile.exists()) {
			throw new MojoExecutionException(
					"schemaFile does not exist: " + (schemaFile != null ? schemaFile.getAbsolutePath() : "<null>"));
		}

		Path queriesRoot = queriesDirectory.toPath();
		Path outRoot = outputDirectory.toPath();
		List<Path> queryFiles = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(queriesRoot)) {
			walk.filter(Files::isRegularFile).filter(AotCompileQueriesMojo::isSupportedQueryFile).sorted()
					.forEach(queryFiles::add);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to scan queriesDirectory: " + queriesRoot, e);
		}

		List<String> failures = new ArrayList<>();
		int compiledCount = 0;

		for (Path qFile : queryFiles) {
			Path rel = queriesRoot.relativize(qFile);
			try {
				CompilerService.QueryLanguage lang = CompilerService.QueryLanguage.detectFromPath(qFile);
				String src = Files.readString(qFile);
				ImpScmNode ast = CompilerService.lowerToAst(src, lang);
				CompilerService.validateAgainstSchema(ast, schemaFile.toPath());

				String ir = ImpScmSerializer.serialize(ast);
				String outName = replaceExtension(rel.toString(), ".impscm");
				Path outFile = outRoot.resolve(outName);
				if (outFile.getParent() != null) {
					Files.createDirectories(outFile.getParent());
				}
				Files.writeString(outFile, ir + "\n");
				compiledCount++;
			} catch (SchemaValidationException sve) {
				failures.add(rel + ": " + sve.getMessage());
			} catch (Exception e) {
				failures.add(rel + ": " + e.getMessage());
			}
		}

		if (!failures.isEmpty()) {
			StringBuilder msg = new StringBuilder("AOT query compilation failed for ").append(failures.size())
					.append(" file(s):\n");
			for (String f : failures) {
				msg.append("  - ").append(f).append('\n');
			}
			throw new MojoFailureException(msg.toString());
		}

		getLog().info("AOT compiled and validated " + compiledCount + " query file(s) -> " + outRoot);
	}

	private static boolean isSupportedQueryFile(Path path) {
		String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return lower.endsWith(".cypher") || lower.endsWith(".impk") || lower.endsWith(".implog")
				|| lower.endsWith(".impscm");
	}

	private static String replaceExtension(String path, String newExt) {
		int dot = path.lastIndexOf('.');
		return dot > 0 ? path.substring(0, dot) + newExt : path + newExt;
	}
}
