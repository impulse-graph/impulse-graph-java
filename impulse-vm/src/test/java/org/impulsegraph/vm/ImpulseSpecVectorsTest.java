package org.impulsegraph.vm;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes all ImpulseVM v0.11.0 specification {@code .impas} test vectors
 * (Pillars 1–5, positive + negative + metamorphic P0..P3) directly inside JUnit
 * 5 for JaCoCo coverage and PITest mutation testing.
 */
public final class ImpulseSpecVectorsTest {

	private record VectorCase(String name, String expectedStatus, boolean metamorphic, String maxSteps,
			String fixtureJson) {
	}

	private static List<VectorCase> cases = List.of();

	private static Path findSpecRepo() {
		Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		Path[] candidates = {cwd.resolve("spec"), cwd.resolve("../impulse-graph-spec"),
				cwd.resolve("../../impulse-graph-spec"), Path.of("/Users/jesse/impulse/impulse-graph-spec")};
		for (Path p : candidates) {
			Path norm = p.normalize();
			if (Files.isDirectory(norm.resolve("test-vectors/vm-impas"))
					&& Files.isRegularFile(norm.resolve("tools/impas_assembler.py"))) {
				return norm;
			}
		}
		return null;
	}

	@BeforeAll
	static void assembleSpecVectors() throws Exception {
		Path specRepo = findSpecRepo();
		Assumptions.assumeTrue(specRepo != null, "impulse-graph-spec repo not found alongside impulse-graph-java");

		String pyScript = """
				import json, pathlib, sys
				spec = pathlib.Path(sys.argv[1])
				sys.path.insert(0, str(spec / 'tools'))
				import run_vm_asm_suite as suite
				from impas_assembler import ImpAsm
				asm = ImpAsm()
				vec_root = spec / 'test-vectors' / 'vm-impas'
				for p in sorted(vec_root.rglob('*.impas')):
				    rel = str(p.relative_to(vec_root))
				    src = p.read_text(encoding='utf-8')
				    expects, raw_words, _ = suite._parse_expectations(src)
				    exp_dict = dict(expects)
				    if 'ASM_ERROR' in exp_dict:
				        continue
				    exp_status = exp_dict.get('STATUS', '')
				    is_pos = (exp_status == 'IMPULSE_VM_OK')
				    max_steps = str(int(exp_dict['MAX_STEPS'], 0)) if 'MAX_STEPS' in exp_dict else '-'
				    if raw_words is not None:
				        words, data_dirs, const_pool = raw_words, [], []
				    else:
				        prog = asm.assemble(src)
				        words, data_dirs, const_pool = prog.words, prog.data_directives, prog.const_pool
				    has_pinned = any(d.get('directive') == '.tier_thresholds' for d in data_dirs)
				    meta = '1' if (is_pos and not has_pinned) else '0'
				    payload = {
				        'data_directives': data_dirs,
				        'const_pool': const_pool,
				        'words_hex': [f'0x{w:016X}' for w in words],
				    }
				    print(f"{rel}\\t{exp_status}\\t{meta}\\t{max_steps}\\t{json.dumps(payload, separators=(',', ':'))}")
				""";

		ProcessBuilder pb = new ProcessBuilder("python3", "-c", pyScript, specRepo.toString());
		pb.redirectErrorStream(false);
		Process proc = pb.start();
		List<VectorCase> loaded = new ArrayList<>(700);
		try (BufferedReader br = new BufferedReader(
				new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = br.readLine()) != null) {
				if (line.isBlank())
					continue;
				String[] parts = line.split("\t", 5);
				if (parts.length == 5) {
					loaded.add(new VectorCase(parts[0], parts[1], "1".equals(parts[2]), parts[3], parts[4]));
				}
			}
		}
		int rc = proc.waitFor();
		if (rc != 0) {
			String err = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
			throw new IllegalStateException("Assembler failed (rc=" + rc + "): " + err);
		}
		cases = loaded;
		assertTrue(cases.size() >= 600, "Expected >= 600 v0.11 spec vectors, got " + cases.size());
	}

	private static String extractJsonString(String json, String key) {
		String needle = "\"" + key + "\":\"";
		int idx = json.indexOf(needle);
		if (idx < 0)
			return "";
		int start = idx + needle.length();
		int end = json.indexOf('"', start);
		return (end > start) ? json.substring(start, end) : "";
	}

	@Test
	void allSpecVectorsPassAndPreserveMetamorphicLogicalHashAcrossP0ToP3() {
		Assumptions.assumeFalse(cases.isEmpty(), "No spec vectors loaded");
		String[] metaProfiles = {"P0", "P1", "P2", "P3"};
		int executed = 0;
		for (VectorCase vc : cases) {
			Long maxSteps = ("-".equals(vc.maxSteps()) || vc.maxSteps().isEmpty())
					? null
					: Long.parseLong(vc.maxSteps());
			String resp0 = VmFixtureBatchMain.runFixtureJson("P0", maxSteps, vc.fixtureJson());
			String st0 = extractJsonString(resp0, "status");
			if (!vc.expectedStatus().isEmpty()) {
				assertEquals(vc.expectedStatus(), st0, "Status mismatch on P0 for " + vc.name());
			} else {
				assertFalse(st0.isEmpty(), "Missing status on P0 for " + vc.name());
			}
			String lh0 = extractJsonString(resp0, "logical_hash");
			assertFalse(lh0.isEmpty(), "Missing logical_hash on " + vc.name());
			executed++;

			if (vc.metamorphic()) {
				for (int pi = 1; pi < metaProfiles.length; pi++) {
					String prof = metaProfiles[pi];
					String resp = VmFixtureBatchMain.runFixtureJson(prof, maxSteps, vc.fixtureJson());
					assertEquals(vc.expectedStatus(), extractJsonString(resp, "status"),
							"Status mismatch on " + prof + " for " + vc.name());
					assertEquals(lh0, extractJsonString(resp, "logical_hash"),
							"Metamorphic logical_hash divergence on " + prof + " for " + vc.name());
					executed++;
				}
			}
		}
		assertTrue(executed >= 900, "Expected >= 900 profile executions, got " + executed);
	}

	@Test
	void allSpecVectorsMatchBetweenInterpreterAndMethodHandleJit() {
		Assumptions.assumeFalse(cases.isEmpty(), "No spec vectors loaded");
		int checked = 0;
		try {
			for (VectorCase vc : cases) {
				Long maxSteps = ("-".equals(vc.maxSteps()) || vc.maxSteps().isEmpty())
						? null
						: Long.parseLong(vc.maxSteps());
				VmFixtureBatchMain.setUseJit(false);
				String interpResp = VmFixtureBatchMain.runFixtureJson("P0", maxSteps, vc.fixtureJson());
				VmFixtureBatchMain.setUseJit(true);
				String jitResp = VmFixtureBatchMain.runFixtureJson("P0", maxSteps, vc.fixtureJson());
				assertEquals(extractJsonString(interpResp, "status"), extractJsonString(jitResp, "status"),
						"JIT vs Interpreter status mismatch on " + vc.name());
				assertEquals(extractJsonString(interpResp, "logical_hash"), extractJsonString(jitResp, "logical_hash"),
						"JIT vs Interpreter logical_hash mismatch on " + vc.name());
				checked++;
			}
		} finally {
			VmFixtureBatchMain.setUseJit(false);
		}
		assertTrue(checked >= 600, "Expected >= 600 JIT parity checks, got " + checked);
	}
}
