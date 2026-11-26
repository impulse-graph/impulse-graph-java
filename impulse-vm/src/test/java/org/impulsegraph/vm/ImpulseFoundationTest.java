package org.impulsegraph.vm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.impulsegraph.vm.ImpulseIsaTables.*;

class ImpulseFoundationTest {

	@Test
	void generatedIsaTablesMatchSpecWithoutDrift() throws Exception {
		Path specGenerated = Path.of("../spec/v0.11-refactor/generated/ImpulseIsaTables.java").toAbsolutePath()
				.normalize();
		Path localCopy = Path.of("src/main/java/org/impulsegraph/vm/ImpulseIsaTables.java").toAbsolutePath()
				.normalize();
		assertThat(specGenerated).exists();
		assertThat(localCopy).exists();
		String actual = Files.readString(localCopy, StandardCharsets.UTF_8).replaceAll("(?m)^\\s*\\*(?!/)\\s*", " ")
				.replaceAll("\\s+", " ").replace("{ }", "{}").trim();
		String expected = Files.readString(specGenerated, StandardCharsets.UTF_8)
				.replaceAll("(?m)^\\s*\\*(?!/)\\s*", " ").replaceAll("\\s+", " ").replace("{ }", "{}").trim();
		assertThat(actual).isEqualTo(expected);
	}

	@Test
	void xxh3MatchesReferenceVectors() {
		// Canonical XXH3_64bits(seed=0) vectors from xxhash.h v0.8.2
		assertThat(ImpulseXxh3.hash64(new byte[0])).isEqualTo(0x2D06800538D394C2L);
		assertThat(ImpulseXxh3.hash64("a".getBytes(StandardCharsets.UTF_8))).isEqualTo(0xE6C632B61E964E1FL);
		assertThat(ImpulseXxh3.hash64("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(0x78AF5F94892F3950L);
		// Non-zero seed on empty input
		long seededEmpty = ImpulseXxh3.hash64WithSeed(new byte[0], ImpulseContextHash.LOGICAL_SEED);
		assertThat(seededEmpty).isNotEqualTo(0x2D06800538D394C2L);
		// Long input (> 240 bytes) exercises stripe + block path
		byte[] longBuf = new byte[1200];
		for (int i = 0; i < longBuf.length; i++) {
			longBuf[i] = (byte) (i * 31 + 7);
		}
		long h1 = ImpulseXxh3.hash64WithSeed(longBuf, ImpulseContextHash.LOGICAL_SEED);
		long h2 = ImpulseXxh3.hash64WithSeed(longBuf, ImpulseContextHash.LOGICAL_SEED);
		assertThat(h1).isEqualTo(h2);
	}

	@Test
	void validatorEnforcesExtensionPairingMbzAndJumpBounds() {
		// Valid 128-bit LOAD_CONST.i64.ext + HALT
		long w1 = ImpulseIsaTables.encode(OP_LOAD_CONST, FLAG_EXTENDED, 4, 0, 0, 0, 0, 42);
		long w2 = ImpulseIsaTables.encodeExtensionWord(0);
		long halt = ImpulseIsaTables.encode(OP_HALT, 0, 0, 0, 0, 0, 0, 0);
		assertThat(ImpulseValidator.validate(new long[]{w1, w2, halt}).isOk()).isTrue();

		// Missing extension word
		assertThat(ImpulseValidator.validate(new long[]{w1}).status()).isEqualTo(IMPULSE_VM_ERR_VALIDATION_FAILED);

		// Stray 0xFF
		assertThat(ImpulseValidator.validate(new long[]{w2, halt}).status())
				.isEqualTo(IMPULSE_VM_ERR_VALIDATION_FAILED);

		// Self-loop JMP
		long selfJmp = ImpulseIsaTables.encode(OP_JMP, 0, 0, 0, 0, 0, 0, 0);
		assertThat(ImpulseValidator.validate(new long[]{selfJmp, halt}).status())
				.isEqualTo(IMPULSE_VM_ERR_OUT_OF_BOUNDS);

		// Non-zero MBZ field on HALT
		long badHalt = ImpulseIsaTables.encode(OP_HALT, 0, 0, 1, 0, 0, 0, 0);
		assertThat(ImpulseValidator.validate(new long[]{badHalt}).status()).isEqualTo(IMPULSE_VM_ERR_INVALID_OPERAND);
	}

	@Test
	void pillar1BasicArithmeticAndWindowCallRet() {
		// R0 = 10, R1 = 32, R2 = R0 + R1, HALT
		long[] prog = new long[]{ImpulseIsaTables.encode(OP_LOAD_CONST, 0, 0, 0, 0, 0, 0, 10),
				ImpulseIsaTables.encode(OP_LOAD_CONST, 0, 0, 1, 0, 0, 0, 32),
				ImpulseIsaTables.encode(OP_SCALAR_INT_ALU, 0, 0, 2, 0, 1, 0, 0),
				ImpulseIsaTables.encode(OP_HALT, 0, 0, 0, 0, 0, 0, 0)};
		ImpulseWorkspace ws = new ImpulseWorkspace(64);
		int status = ImpulseInterpreter.executeValidated(prog, null, ws, 1000L);
		assertThat(status).isEqualTo(IMPULSE_VM_OK);
		assertThat(ws.isRValid(2)).isTrue();
		assertThat(ws.rRegs[2]).isEqualTo(42L);
		assertThat(ws.readRType(2)).isEqualTo(SCALAR_I64);
		ImpulseContextHash.Digest digest = ImpulseContextHash.compute(ws);
		assertThat(digest.logicalHash()).isNotEqualTo(0L);
	}

	@Test
	void sweSweepFrontierSelfCopyPreservesTier1Paged() {
		ImpulseFrontier f = new ImpulseFrontier(300_000);
		f.resetEmpty(0, 300_000);
		// Add 65 nodes to promote from TIER0_INLINE (max 64) to TIER1_PAGED
		// (dirtyPageCount >= 0)
		for (int i = 0; i < 65; i++) {
			f.add(i * 10);
		}
		assertThat(f.tier).isEqualTo(ImpulseFrontier.TIER1_PAGED);
		f.copyFrom(f);
		assertThat(f.cardinality()).isEqualTo(65);
		assertThat(f.contains(0)).isTrue();
		assertThat(f.contains(640)).isTrue();
	}

	@Test
	void sweSweepTier1InitFromValidatedIdsSupportsContainsAndAdd() {
		ImpulseFrontier f = new ImpulseFrontier(100);
		// Under P1_FORCE_TIER1 (tier0Max=2, pageShift=2, tier2Denom=2), 3 nodes in
		// domCard=100 -> TIER1_PAGED
		f.initFromValidatedIds(0, 100, new int[]{1, 10, 50}, ImpulseGeometry.P1_FORCE_TIER1, true);
		assertThat(f.tier).isEqualTo(ImpulseFrontier.TIER1_PAGED);
		assertThat(f.contains(10)).isTrue();
		assertThat(f.contains(50)).isTrue();
		assertThat(f.contains(11)).isFalse();
		f.add(75);
		assertThat(f.contains(10)).isTrue();
		assertThat(f.contains(75)).isTrue();
		assertThat(f.cardinality()).isEqualTo(4);
	}

	@Test
	void sweSweepValidatorConvergingBranchesDoNotOverflowDfsStack() {
		// SAMPLE_NEIGHBORS.vec_wr followed by converging JCC branches that push the
		// same join PC multiple times
		int k = 12;
		long[] prog = new long[k + 3];
		prog[0] = ImpulseIsaTables.encode(OP_SAMPLE_NEIGHBORS, 0, 1, 1, 0, 0, 0, 1);
		for (int i = 1; i <= k; i++) {
			// JCC.jz -> jump to join pc (k + 1)
			prog[i] = ImpulseIsaTables.encode(OP_JCC, 0, 0, 0, 0, 0, 0, (k + 1) - i);
		}
		prog[k + 1] = ImpulseIsaTables.encode(OP_NOP, 0, 0, 0, 0, 0, 0, 0);
		prog[k + 2] = ImpulseIsaTables.encode(OP_HALT, 0, 0, 0, 0, 0, 0, 0);
		assertThat(ImpulseValidator.validate(prog).isOk()).isTrue();
	}

	@Test
	void sweSweepMiniJsonParserPreservesUnsigned64BitMax() {
		// LOAD_PARAM.scalar R0, param=0, type=u64; HALT
		long load = ImpulseIsaTables.encode(OP_LOAD_PARAM, 0, 0, 0, 0, 0, 0, (SCALAR_U64 << 16));
		long halt = ImpulseIsaTables.encode(OP_HALT, 0, 0, 0, 0, 0, 0, 0);
		String json = String.format(
				"{\"data_directives\":[{\"directive\":\".param\",\"args\":[0],\"kv\":{\"type\":\"u64\",\"value\":18446744073709551615}}],"
						+ "\"const_pool\":[],\"words_hex\":[\"0x%016X\",\"0x%016X\"]}",
				load, halt);
		String resp = VmFixtureBatchMain.runFixtureJson("P0", null, json);
		assertThat(resp).contains("\"R0\":-1,");
	}
}
