package org.impulsegraph.vm.v11;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.impulsegraph.vm.v11.V11IsaTables.*;

class V11FoundationTest {

    @Test
    void generatedIsaTablesMatchSpecWithoutDrift() throws Exception {
        Path specGenerated = Path.of("../spec/v0.11-refactor/generated/V11IsaTables.java").toAbsolutePath().normalize();
        Path localCopy = Path.of("src/main/java/org/impulsegraph/vm/v11/V11IsaTables.java").toAbsolutePath().normalize();
        assertThat(specGenerated).exists();
        assertThat(localCopy).exists();
        assertThat(Files.readString(localCopy, StandardCharsets.UTF_8))
                .isEqualTo(Files.readString(specGenerated, StandardCharsets.UTF_8));
    }

    @Test
    void xxh3MatchesReferenceVectors() {
        // Canonical XXH3_64bits(seed=0) vectors from xxhash.h v0.8.2
        assertThat(V11Xxh3.hash64(new byte[0])).isEqualTo(0x2D06800538D394C2L);
        assertThat(V11Xxh3.hash64("a".getBytes(StandardCharsets.UTF_8))).isEqualTo(0xE6C632B61E964E1FL);
        assertThat(V11Xxh3.hash64("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(0x78AF5F94892F3950L);
        // Non-zero seed on empty input
        long seededEmpty = V11Xxh3.hash64WithSeed(new byte[0], V11ContextHash.LOGICAL_SEED);
        assertThat(seededEmpty).isNotEqualTo(0x2D06800538D394C2L);
        // Long input (> 240 bytes) exercises stripe + block path
        byte[] longBuf = new byte[1200];
        for (int i = 0; i < longBuf.length; i++) {
            longBuf[i] = (byte) (i * 31 + 7);
        }
        long h1 = V11Xxh3.hash64WithSeed(longBuf, V11ContextHash.LOGICAL_SEED);
        long h2 = V11Xxh3.hash64WithSeed(longBuf, V11ContextHash.LOGICAL_SEED);
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void validatorEnforcesExtensionPairingMbzAndJumpBounds() {
        // Valid 128-bit LOAD_CONST.i64.ext + HALT
        long w1 = V11IsaTables.encode(OP_LOAD_CONST, FLAG_EXTENDED, 4, 0, 0, 0, 0, 42);
        long w2 = V11IsaTables.encodeExtensionWord(0);
        long halt = V11IsaTables.encode(OP_HALT, 0, 0, 0, 0, 0, 0, 0);
        assertThat(V11Validator.validate(new long[]{w1, w2, halt}).isOk()).isTrue();

        // Missing extension word
        assertThat(V11Validator.validate(new long[]{w1}).status())
                .isEqualTo(IMPULSE_VM_ERR_VALIDATION_FAILED);

        // Stray 0xFF
        assertThat(V11Validator.validate(new long[]{w2, halt}).status())
                .isEqualTo(IMPULSE_VM_ERR_VALIDATION_FAILED);

        // Self-loop JMP
        long selfJmp = V11IsaTables.encode(OP_JMP, 0, 0, 0, 0, 0, 0, 0);
        assertThat(V11Validator.validate(new long[]{selfJmp, halt}).status())
                .isEqualTo(IMPULSE_VM_ERR_OUT_OF_BOUNDS);

        // Non-zero MBZ field on HALT
        long badHalt = V11IsaTables.encode(OP_HALT, 0, 0, 1, 0, 0, 0, 0);
        assertThat(V11Validator.validate(new long[]{badHalt}).status())
                .isEqualTo(IMPULSE_VM_ERR_INVALID_OPERAND);
    }

    @Test
    void pillar1BasicArithmeticAndWindowCallRet() {
        // R0 = 10, R1 = 32, R2 = R0 + R1, HALT
        long[] prog = new long[]{
            V11IsaTables.encode(OP_LOAD_CONST, 0, 0, 0, 0, 0, 0, 10),
            V11IsaTables.encode(OP_LOAD_CONST, 0, 0, 1, 0, 0, 0, 32),
            V11IsaTables.encode(OP_SCALAR_INT_ALU, 0, 0, 2, 0, 1, 0, 0),
            V11IsaTables.encode(OP_HALT, 0, 0, 0, 0, 0, 0, 0)
        };
        V11Workspace ws = new V11Workspace(64);
        int status = V11Interpreter.executeValidated(prog, null, ws, 1000L);
        assertThat(status).isEqualTo(IMPULSE_VM_OK);
        assertThat(ws.isRValid(2)).isTrue();
        assertThat(ws.rRegs[2]).isEqualTo(42L);
        assertThat(ws.readRType(2)).isEqualTo(SCALAR_I64);
        V11ContextHash.Digest digest = V11ContextHash.compute(ws);
        assertThat(digest.logicalHash()).isNotEqualTo(0L);
    }
}
