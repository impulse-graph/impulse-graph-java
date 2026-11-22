package org.impulsegraph.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ImpulseCliTest {

	@TempDir
	static Path sharedDir;

	static Path manifestYaml;
	static Path snapshotPath;

	private record CliResult(int exitCode, String out, String err) {
	}

	private static CliResult exec(String... args) {
		CommandLine cmd = ImpulseCliMain.createCommandLine();
		StringWriter outSw = new StringWriter();
		StringWriter errSw = new StringWriter();
		cmd.setOut(new PrintWriter(outSw));
		cmd.setErr(new PrintWriter(errSw));
		int code = cmd.execute(args);
		return new CliResult(code, outSw.toString(), errSw.toString());
	}

	@BeforeAll
	static void setupFixtures() throws IOException {
		Path domainsDir = sharedDir.resolve("domains");
		Path relsDir = sharedDir.resolve("relations");
		Files.createDirectories(domainsDir);
		Files.createDirectories(relsDir);

		Files.writeString(domainsDir.resolve("users.csv"),
				"id,name,age\n" + "u0,Alice,30\n" + "u1,Bob,25\n" + "u2,Carol,42\n" + "u3,Dave,19\n");

		Files.writeString(relsDir.resolve("follows.csv"),
				"src,tgt,weight\n" + "u0,u1,1.0\n" + "u0,u2,0.5\n" + "u1,u2,2.0\n" + "u2,u3,1.5\n");

		manifestYaml = sharedDir.resolve("manifest.yaml");
		Files.writeString(manifestYaml, """
				name: SocialGraph
				version: "1.0"
				domains:
				  - id: 0
				    name: User
				    key_type: string
				    id_column: id
				    file: domains/users.csv
				    attributes:
				      - name: name
				        type: string
				      - name: age
				        type: int32
				relations:
				  - id: 0
				    name: FOLLOWS
				    from: User
				    to: User
				    include_csc: true
				    file: relations/follows.csv
				    attributes:
				      - name: weight
				        type: float32
				""");

		snapshotPath = sharedDir.resolve("social.imps");
		CliResult buildRes = exec("build", "-m", manifestYaml.toString(), "-o", snapshotPath.toString());
		assertThat(buildRes.exitCode()).withFailMessage("build failed: %s", buildRes.err()).isEqualTo(0);
		assertThat(snapshotPath).exists();
	}

	@Test
	void inspectTextJsonPageMapAndAnonymize() {
		CliResult textRes = exec("inspect", snapshotPath.toString(), "--page-map");
		assertThat(textRes.exitCode()).isEqualTo(0);
		assertThat(textRes.out()).contains("0x494D5053").contains("User").contains("FOLLOWS")
				.contains("Page / Alignment Map");

		CliResult jsonRes = exec("inspect", snapshotPath.toString(), "--format", "json", "--page-map", "--domain",
				"User", "--relation", "FOLLOWS");
		assertThat(jsonRes.exitCode()).isEqualTo(0);
		assertThat(jsonRes.out()).contains("\"magic\": \"0x494D5053\"").contains("\"pageMap\"")
				.contains("\"hasCsc\": true");

		CliResult anonRes = exec("inspect", snapshotPath.toString(), "--anonymize");
		assertThat(anonRes.exitCode()).isEqualTo(0);
		assertThat(anonRes.out()).contains("domain_0").contains("rel_0").doesNotContain("User")
				.doesNotContain("FOLLOWS");

		CliResult anonJsonRes = exec("inspect", snapshotPath.toString(), "--anonymize", "--format", "json");
		assertThat(anonJsonRes.exitCode()).isEqualTo(0);
		assertThat(anonJsonRes.out()).contains("domain_0").contains("rel_0").doesNotContain("User")
				.doesNotContain("FOLLOWS");
	}

	@Test
	void snapshotValidateStrictAndDeepAudit(@TempDir Path tmp) throws IOException {
		CliResult okRes = exec("snapshot", "validate", snapshotPath.toString(), "--strict-alignment", "--deep-audit");
		assertThat(okRes.exitCode()).withFailMessage("validate failed: %s\n%s", okRes.out(), okRes.err()).isEqualTo(0);
		assertThat(okRes.out()).contains("Status : PASS").contains("Deep audit CSC transpose consistency verified");

		CliResult jsonRes = exec("snapshot", "validate", snapshotPath.toString(), "--quick-check", "--format", "json");
		assertThat(jsonRes.exitCode()).isEqualTo(0);
		assertThat(jsonRes.out()).contains("\"valid\": true");

		Path badFile = tmp.resolve("corrupt.imps");
		Files.write(badFile, new byte[64]);
		CliResult badRes = exec("snapshot", "validate", badFile.toString());
		assertThat(badRes.exitCode()).isNotEqualTo(0);
		assertThat(badRes.out()).contains("Status : FAIL");
	}

	@Test
	void statsTextAndJson() {
		CliResult textRes = exec("stats", snapshotPath.toString(), "--verbose", "--supernode-threshold", "2.5");
		assertThat(textRes.exitCode()).isEqualTo(0);
		assertThat(textRes.out()).contains("User: 4 nodes").contains("FOLLOWS").contains("stdDev=");

		CliResult jsonRes = exec("snapshot", "stats", snapshotPath.toString(), "--format", "json");
		assertThat(jsonRes.exitCode()).isEqualTo(0);
		assertThat(jsonRes.out()).contains("\"edgeCount\": 4").contains("\"p50Degree\"");
	}

	@Test
	void schemaExtractValidateAndMultiConsumerCompatGate(@TempDir Path tmp) throws IOException {
		Path extractedYaml = tmp.resolve("extracted.imps.schema.yaml");
		CliResult extractRes = exec("schema", "extract", snapshotPath.toString(), "-o", extractedYaml.toString());
		assertThat(extractRes.exitCode()).isEqualTo(0);
		assertThat(extractedYaml).exists();

		String extractedContent = Files.readString(extractedYaml);
		assertThat(extractedContent).contains("User:").contains("FOLLOWS:");

		CliResult validateRes = exec("schema", "validate", extractedYaml.toString(), "--snapshot",
				snapshotPath.toString());
		assertThat(validateRes.exitCode()).withFailMessage("validate err: %s\n%s", validateRes.out(), validateRes.err())
				.isEqualTo(0);
		assertThat(validateRes.out()).contains("Status     : PASS");

		// Multi-consumer passing gate (v1 + v2)
		Path consumerV1 = tmp.resolve("consumer-v1.yaml");
		Files.writeString(consumerV1, """
				graphName: ConsumerV1
				version: "1.0"
				domains:
				  User:
				    attributes:
				      age: int32
				relations:
				  FOLLOWS:
				    source: User
				    target: User
				    directions: [out]
				""");

		Path consumerV2 = tmp.resolve("consumer-v2.yaml");
		Files.writeString(consumerV2, """
				graphName: ConsumerV2
				version: "2.0"
				domains:
				  User:
				    attributes:
				      name: var_string
				      age: int32
				relations:
				  FOLLOWS:
				    source: User
				    target: User
				    directions: [out, in]
				""");

		CliResult compatPass = exec("schema", "check-compat", "--snapshot", snapshotPath.toString(), "--consumer",
				consumerV1.toString(), "--consumer", consumerV2.toString(), "--format", "json");
		assertThat(compatPass.exitCode()).withFailMessage("compatPass failed: %s", compatPass.out()).isEqualTo(0);
		assertThat(compatPass.out()).contains("\"compatible\": true");

		// Multi-consumer failing gate (consumerV3 demands missing attribute)
		Path consumerV3 = tmp.resolve("consumer-v3-breaking.yaml");
		Files.writeString(consumerV3, """
				graphName: ConsumerV3
				version: "3.0"
				domains:
				  User:
				    attributes:
				      nonexistent_score: float64
				relations:
				  FOLLOWS:
				    source: User
				    target: User
				    directions: [out]
				""");

		CliResult compatFail = exec("schema", "check-compat", "--snapshot", snapshotPath.toString(), "--consumer",
				consumerV1.toString(), "--consumer", consumerV3.toString());
		assertThat(compatFail.exitCode()).isNotEqualTo(0);
		assertThat(compatFail.out()).contains("Overall  : INCOMPATIBLE").contains("MISSING_ATTRIBUTE");
	}

	@Test
	void compileAssembleDisassembleExplainAndRun(@TempDir Path tmp) throws IOException {
		Path schemaYaml = tmp.resolve("schema.yaml");
		exec("schema", "extract", snapshotPath.toString(), "-o", schemaYaml.toString());

		Path cypherFile = tmp.resolve("friends.cypher");
		Files.writeString(cypherFile, "MATCH (u:User)-[:FOLLOWS]->(f:User) WHERE u.id = 0 RETURN f");

		// Compile to IR with schema validation
		Path irOut = tmp.resolve("friends.impscm");
		CliResult irRes = exec("compile", cypherFile.toString(), "--schema", schemaYaml.toString(), "--emit-ir", "-o",
				irOut.toString());
		assertThat(irRes.exitCode()).withFailMessage("compile IR err: %s", irRes.err()).isEqualTo(0);
		assertThat(irOut).exists();
		assertThat(Files.readString(irOut)).contains("FOLLOWS");

		// Compile to .impb with snapshot
		Path impbOut = tmp.resolve("friends.impb");
		CliResult impbRes = exec("compile", cypherFile.toString(), "--schema", schemaYaml.toString(), "--snapshot",
				snapshotPath.toString(), "-o", impbOut.toString());
		assertThat(impbRes.exitCode()).withFailMessage("compile impb err: %s", impbRes.err()).isEqualTo(0);
		assertThat(impbOut).exists();
		assertThat(Files.size(impbOut)).isGreaterThan(0).isEqualTo((Files.size(impbOut) / 8) * 8);

		// Disassemble .impb -> .impas
		Path disasmFile = tmp.resolve("friends.impas");
		CliResult disRes = exec("disassemble", "-i", impbOut.toString(), "-o", disasmFile.toString());
		assertThat(disRes.exitCode()).isEqualTo(0);
		assertThat(Files.readString(disasmFile)).contains("OP_HALT");

		// Assemble simple hand-written .impas -> .impb -> disassemble
		Path asmIn = tmp.resolve("simple.impas");
		Files.writeString(asmIn, """
				OP_INIT_INPUT_NODE dst=R0
				OP_CSR_WALK dst=R1, payload=0x00000000
				OP_COLLECT_BITSET dst=R1
				OP_HALT
				""");
		Path asmOut = tmp.resolve("simple.impb");
		CliResult asmRes = exec("assemble", "-i", asmIn.toString(), "-o", asmOut.toString());
		assertThat(asmRes.exitCode()).withFailMessage("assemble err: %s", asmRes.err()).isEqualTo(0);
		assertThat(Files.size(asmOut)).isEqualTo(32L);

		CliResult disSimple = exec("disassemble", "-i", asmOut.toString());
		assertThat(disSimple.exitCode()).isEqualTo(0);
		assertThat(disSimple.out()).contains("OP_INIT_INPUT_NODE").contains("OP_CSR_WALK").contains("OP_HALT");

		// Explain with snapshot and schema
		CliResult explainRes = exec("explain", cypherFile.toString(), "--snapshot", snapshotPath.toString(), "--schema",
				schemaYaml.toString());
		assertThat(explainRes.exitCode()).withFailMessage("explain err: %s", explainRes.err()).isEqualTo(0);
		assertThat(explainRes.out()).contains("IMPULSE QUERY EXPLAIN DIAGNOSTIC REPORT");

		// Run .impb with --verify
		CliResult runImpb = exec("run", "-s", snapshotPath.toString(), "-b", asmOut.toString(), "-i", "0", "--verify",
				"--format", "json");
		assertThat(runImpb.exitCode()).withFailMessage("run impb err: %s", runImpb.err()).isEqualTo(0);
		assertThat(runImpb.out()).contains("\"verified\": true").contains("\"cardinality\": 2");

		// Run .cypher with --verify
		CliResult runCypher = exec("run", "-s", snapshotPath.toString(), "-b", cypherFile.toString(), "-i", "0",
				"--verify");
		assertThat(runCypher.exitCode()).withFailMessage("run cypher err: %s", runCypher.err()).isEqualTo(0);
		assertThat(runCypher.out()).contains("Verified     : true").contains("cardinality=2");
	}

	@Test
	void codegenFromYamlAndFromSnapshot(@TempDir Path tmp) throws IOException {
		Path yamlSchema = tmp.resolve("codegen-schema.yaml");
		Files.writeString(yamlSchema, """
				packageName: com.example.social
				graphName: Social
				domains:
				  User:
				    attributes:
				      age: INT32
				relations:
				  FOLLOWS:
				    source: User
				    target: User
				""");

		Path outFromYaml = tmp.resolve("gen-yaml");
		CliResult resYaml = exec("codegen", "--schema", yamlSchema.toString(), "--out", outFromYaml.toString(),
				"--package", "com.example.social");
		assertThat(resYaml.exitCode()).withFailMessage("codegen yaml err: %s", resYaml.err()).isEqualTo(0);
		assertThat(outFromYaml.resolve("com/example/social/SocialSnapshot.java")).exists();
	}
}
