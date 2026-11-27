package org.impulsegraph.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class CodegenVerificationTest {

	@TempDir
	Path tempDir;

	@Test
	public void testSocialGraphGeneration() throws IOException {
		Path yamlPath = Paths.get("src/test/resources/schemas/social-graph.yaml");
		GeneratorMain.generate(yamlPath.toString(), tempDir.toString());

		Path generatedFile = tempDir.resolve("org/impulsegraph/example/social/SocialGraphSnapshot.java");
		assertTrue(generatedFile.toFile().exists(), "Generated SocialGraphSnapshot.java should exist");
	}

	@Test
	public void testMinimalGraphGeneration() throws IOException {
		Path yamlPath = Paths.get("src/test/resources/schemas/minimal-graph.yaml");
		GeneratorMain.generate(yamlPath.toString(), tempDir.toString());

		Path generatedFile = tempDir.resolve("org/impulsegraph/example/minimal/MinimalGraphSnapshot.java");
		assertTrue(generatedFile.toFile().exists(), "Generated MinimalGraphSnapshot.java should exist");
	}

	@Test
	public void testDecimal64AttributeCodegen() throws IOException {
		String yaml = """
				graphName: LedgerGraph
				version: 1.0
				package: org.impulsegraph.example.ledger
				domains:
				  Account:
				    attributes:
				      balance: DECIMAL(10, 2)
				      notional: DECIMAL64(18, 2)
				      fee: 0x15
				      adjustment: 0x95
				      active: BOOLEAN
				relations:
				  transfer:
				    source: Account
				    target: Account
				""";
		Path yamlPath = tempDir.resolve("ledger-graph.yaml");
		Files.writeString(yamlPath, yaml);

		Path outDir = tempDir.resolve("out");
		GeneratorMain.generate(yamlPath.toString(), outDir.toString());

		Path qbFile = outDir.resolve("org/impulsegraph/example/ledger/AccountQueryBuilder.java");
		assertTrue(Files.exists(qbFile), "Generated AccountQueryBuilder.java should exist");
		String code = Files.readString(qbFile);
		assertTrue(code.contains("import org.impulsegraph.api.Decimal64;"), "Should import Decimal64");
		assertTrue(code.contains("public AccountQueryBuilder<R> filterBalance(String op, Decimal64 val)"),
				"DECIMAL(10, 2) should map to Decimal64");
		assertTrue(code.contains("public AccountQueryBuilder<R> filterNotional(String op, Decimal64 val)"),
				"DECIMAL64(18, 2) should map to Decimal64");
		assertTrue(code.contains("public AccountQueryBuilder<R> filterFee(String op, Decimal64 val)"),
				"0x15 should map to Decimal64");
		assertTrue(code.contains("public AccountQueryBuilder<R> filterAdjustment(String op, Decimal64 val)"),
				"0x95 should map to Decimal64");
		assertTrue(code.contains(
				"decimal(\\\"\" + val.toDecimalString() + \"\\\", \" + val.precision() + \", \" + val.scale() + \")"),
				"Should emit exact CEL decimal(\"digits\", P, S) literal");
		assertTrue(code.contains("public AccountQueryBuilder<R> filterActive(String op, Object val)"),
				"Unmapped types should fall back to Object");
	}
}
