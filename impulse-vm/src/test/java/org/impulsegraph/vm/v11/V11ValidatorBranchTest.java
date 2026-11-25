package org.impulsegraph.vm.v11;

import org.junit.jupiter.api.Test;

import static org.impulsegraph.vm.v11.V11IsaTables.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive branch & boundary unit tests for {@link V11Validator}, {@link V11Interpreter},
 * and {@link V11Vector} covering every MBZ rule, jump/CFG-cycle check, and morsel
 * uninitialized-register validation matrix.
 */
public final class V11ValidatorBranchTest {

    private static long enc(int op, int flags, int subop, int dst, int s1, int s2, int s3, int imm32) {
        return V11Interpreter.encode(op, flags, subop, dst, s1, s2, s3, imm32);
    }

    private static int valOne(long w) {
        return V11Validator.validate(new long[]{w, enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0)}).status();
    }

    private static void checkMorselBody(long bodyOp, int expected) {
        long[] prog = {
            enc(OP_WALK_MORSEL, 0, 0, 0, 0, 0, 0, (2 << 16)),
            bodyOp,
            enc(OP_MORSEL_END, 0, 0, 0, 0, 0, 0, (2 << 16)),
            enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0),
        };
        assertEquals(expected, V11Validator.validate(prog).status(),
                String.format("op=0x%02X sub=%d flg=%d", (int) (bodyOp & 0xFFL),
                        (int) ((bodyOp >>> 8) & 0xFL), (int) ((bodyOp >>> 12) & 0xFL)));
    }

    @Test
    void validatorMbzAndJumpBoundaryMatrix() {
        assertEquals(IMPULSE_VM_ERR_VALIDATION_FAILED, V11Validator.validate(null).status());
        assertEquals(IMPULSE_VM_ERR_VALIDATION_FAILED, V11Validator.validate(new long[0]).status());

        // HALT / NOP / RET MBZ
        assertEquals(IMPULSE_VM_OK, valOne(enc(OP_NOP, 0, 0, 0, 0, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_NOP, 0, 0, 1, 0, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_NOP, 0, 0, 0, 1, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_NOP, 0, 0, 0, 0, 1, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_NOP, 0, 0, 0, 0, 0, 1, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_NOP, 0, 0, 0, 0, 0, 0, 1)));

        // EXTENSION_PAYLOAD pairing & MBZ
        assertEquals(IMPULSE_VM_OK, V11Validator.validate(new long[]{
            enc(OP_LOAD_CONST, FLAG_EXTENDED, 4, 0, 0, 0, 0, 0),
            enc(OP_EXTENSION_PAYLOAD, 0, 0, 0, 0, 0, 0, 42),
            enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0)
        }).status());
        long[] badExts = {
            enc(OP_EXTENSION_PAYLOAD, 1, 0, 0, 0, 0, 0, 0),
            enc(OP_EXTENSION_PAYLOAD, 0, 1, 0, 0, 0, 0, 0),
            enc(OP_EXTENSION_PAYLOAD, 0, 0, 1, 0, 0, 0, 0),
            enc(OP_EXTENSION_PAYLOAD, 0, 0, 0, 1, 0, 0, 0),
            enc(OP_EXTENSION_PAYLOAD, 0, 0, 0, 0, 1, 0, 0),
            enc(OP_EXTENSION_PAYLOAD, 0, 0, 0, 0, 0, 1, 0),
        };
        for (long bw : badExts) {
            assertNotEquals(IMPULSE_VM_OK, V11Validator.validate(new long[]{
                enc(OP_LOAD_CONST, FLAG_EXTENDED, 4, 0, 0, 0, 0, 0), bw, enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0)
            }).status());
        }
        // Dangling FLAG_EXTENDED at end of program / standalone 0xFF
        assertEquals(IMPULSE_VM_ERR_VALIDATION_FAILED, V11Validator.validate(new long[]{
            enc(OP_LOAD_CONST, FLAG_EXTENDED, 4, 0, 0, 0, 0, 0)
        }).status());
        assertEquals(IMPULSE_VM_ERR_VALIDATION_FAILED, valOne(enc(OP_EXTENSION_PAYLOAD, 0, 0, 0, 0, 0, 0, 0)));

        // LOAD_CONST MBZ
        assertEquals(IMPULSE_VM_OK, valOne(enc(OP_LOAD_CONST, 0, 7, 0, 0, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_LOAD_CONST, 0, 7, 0, 0, 0, 0, 1)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_LOAD_CONST, 0, 0, 0, 1, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_LOAD_CONST, 0, 0, 0, 0, 1, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_LOAD_CONST, 0, 0, 0, 0, 0, 1, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_LOAD_CONST, 0, 4, 0, 0, 0, 0, 0)));

        // SPILL / RELOAD MBZ
        assertEquals(IMPULSE_VM_OK, valOne(enc(OP_SPILL, 0, 0, 0, 0, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_SPILL, 0, 0, 0, 0, 0, 0, 16)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_SPILL, 0, 0, 0, 0, 0, 0, 1 << 16)));

        // JMP MBZ & targets (0, negative OOB, positive OOB, jump to 0xFF)
        assertEquals(IMPULSE_VM_OK, valOne(enc(OP_JMP, 0, 0, 0, 0, 0, 0, 1)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_JMP, 0, 0, 1, 0, 0, 0, 1)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_JMP, 0, 0, 0, 1, 0, 0, 1)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_JMP, 0, 0, 0, 0, 1, 0, 1)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_JMP, 0, 0, 0, 0, 0, 1, 1)));
        assertEquals(IMPULSE_VM_ERR_OUT_OF_BOUNDS, valOne(enc(OP_JMP, 0, 0, 0, 0, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_OUT_OF_BOUNDS, valOne(enc(OP_JMP, 0, 0, 0, 0, 0, 0, -1)));
        assertEquals(IMPULSE_VM_ERR_OUT_OF_BOUNDS, valOne(enc(OP_JMP, 0, 0, 0, 0, 0, 0, 10)));
        assertEquals(IMPULSE_VM_ERR_VALIDATION_FAILED, V11Validator.validate(new long[]{
            enc(OP_JMP, 0, 0, 0, 0, 0, 0, 2),
            enc(OP_LOAD_CONST, FLAG_EXTENDED, 4, 0, 0, 0, 0, 0),
            enc(OP_EXTENSION_PAYLOAD, 0, 0, 0, 0, 0, 0, 0),
            enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0)
        }).status());

        // SPMV / VEC_CMP / VEC_TERNARY / LANE_WALK / LANE_STATE_WALK MBZ
        assertEquals(IMPULSE_VM_OK, valOne(enc(OP_SPMV, FLAG_ACCUM, 0, 0, 0, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_SPMV, FLAG_ACCUM, 7, 0, 0, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_SPMV, 0, 0, 0, 0, 0, 1, 0)));
        assertEquals(IMPULSE_VM_OK, valOne(enc(OP_SPMV, FLAG_MASKED_S3, 0, 0, 0, 0, 1, 0)));

        assertEquals(IMPULSE_VM_OK, valOne(enc(OP_VEC_CMP, 0, 6, 0, 0, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_VEC_CMP, FLAG_MASKED_S3, 6, 0, 0, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_VEC_CMP, 0, 6, 0, 0, 0, 0, 1)));
        assertEquals(IMPULSE_VM_OK, valOne(enc(OP_VEC_CMP, 0, 7, 0, 0, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_VEC_CMP, FLAG_SCALAR_RHS, 7, 0, 0, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_VEC_CMP, 0, 7, 0, 0, 0, 1, 0)));
        assertEquals(IMPULSE_VM_OK, valOne(enc(OP_VEC_CMP, FLAG_MASKED_S3, 7, 0, 0, 0, 1, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_VEC_CMP, 0, 0, 0, 0, 0, 1, 0)));
        assertEquals(IMPULSE_VM_OK, valOne(enc(OP_VEC_CMP, FLAG_MASKED_S3, 0, 0, 0, 0, 1, 0)));

        assertEquals(IMPULSE_VM_OK, valOne(enc(OP_VEC_TERNARY, 0, 8, 0, 0, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_VEC_TERNARY, FLAG_SCALAR_RHS, 8, 0, 0, 0, 0, 0)));
        assertEquals(IMPULSE_VM_OK, valOne(enc(OP_VEC_TERNARY, FLAG_SCALAR_RHS, 0, 0, 0, 0, 0, 0)));

        assertEquals(IMPULSE_VM_OK, valOne(enc(OP_LANE_WALK, 0, 8, 0, 0, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_LANE_WALK, 0, 8, 0, 0, 1, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_LANE_WALK, 0, 0, 0, 0, 0, 1, 0)));
        assertEquals(IMPULSE_VM_OK, valOne(enc(OP_LANE_WALK, FLAG_MASKED_S3, 0, 0, 0, 0, 1, 0)));

        assertEquals(IMPULSE_VM_OK, valOne(enc(OP_LANE_STATE_WALK, 0, 3, 0, 0, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_LANE_STATE_WALK, FLAG_SCALAR_RHS, 3, 0, 0, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_LANE_STATE_WALK, FLAG_SCALAR_RHS, 8, 0, 0, 0, 0, 0)));
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, valOne(enc(OP_LANE_STATE_WALK, 0, 0, 0, 0, 0, 1, 0)));
        assertEquals(IMPULSE_VM_OK, valOne(enc(OP_LANE_STATE_WALK, FLAG_MASKED_S3, 0, 0, 0, 0, 1, 0)));
    }

    @Test
    void validatorMorselUninitializedReadMatrix() {
        checkMorselBody(enc(OP_MOV, 0, 1, 8, 8, 0, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_MOV, 0, 2, 12, 8, 0, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_MOV, 0, 2, 12, 13, 0, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_SWAP, 0, 1, 8, 8, 0, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_SWAP, 0, 2, 8, 9, 0, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_SWAP, 0, 2, 12, 8, 0, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_SWAP, 0, 2, 8, 12, 0, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_SPILL, 0, 2, 0, 8, 0, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_SPILL, 0, 2, 0, 12, 0, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_SPILL, 0, 1, 0, 8, 0, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_SET_CONVERT, 0, 0, 12, 8, 0, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_SET_CONVERT, 0, 1, 8, 8, 0, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_SET_CONVERT, 0, 1, 8, 12, 0, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_SET_CONVERT, 0, 2, 8, 12, 0, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_GATHER_NODE_ATTR, 0, 0, 12, 8, 0, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_GATHER_NODE_ATTR, 0, 0, 12, 13, 0, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_GATHER_EDGE_ATTR, 0, 0, 12, 8, 0, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_GATHER_EDGE_ATTR, 0, 0, 12, 13, 0, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_GATHER_VEC, 0, 0, 12, 8, 9, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_GATHER_VEC, 0, 0, 12, 13, 8, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_GATHER_VEC, 0, 0, 12, 8, 13, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_CMP, 0, 0, 12, 8, 9, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_CMP, 0, 0, 12, 13, 8, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_CMP, 0, 0, 12, 8, 13, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_CMP, FLAG_SCALAR_RHS, 0, 12, 8, 13, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_CMP, 0, 6, 12, 8, 9, 13, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_CMP, FLAG_SCALAR_RHS, 6, 12, 8, 13, 14, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_CMP, 0, 7, 12, 8, 13, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_3VL_OP, 0, 0, 12, 13, 8, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_3VL_OP, 0, 0, 12, 8, 13, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_3VL_OP, 0, 1, 12, 8, 13, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_3VL_OP, 0, 3, 12, 8, 13, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_3VL_OP, 0, 2, 12, 8, 13, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_3VL_OP, FLAG_SCALAR_RHS, 0, 12, 8, 13, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_STR_PRED, 0, 0, 12, 13, 8, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_STR_PRED, 0, 0, 12, 8, 13, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_STR_PRED, FLAG_SCALAR_RHS, 0, 12, 8, 13, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_COALESCE, 0, 0, 12, 13, 8, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_COALESCE, 0, 0, 12, 8, 13, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_COALESCE, FLAG_SCALAR_RHS, 0, 12, 8, 13, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_MATH_ARITH, 0, 0, 12, 13, 8, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_MATH_ARITH, 0, 0, 12, 8, 13, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_MATH_ARITH, FLAG_SCALAR_RHS, 0, 12, 8, 13, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_MATH_ARITH, 0, 8, 12, 8, 13, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_MATH_EXP_LOG, 0, 8, 12, 13, 8, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_MATH_EXP_LOG, 0, 8, 12, 8, 13, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_MATH_EXP_LOG, 0, 10, 12, 8, 13, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_MATH_EXP_LOG, FLAG_SCALAR_RHS, 8, 12, 8, 13, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_MATH_EXP_LOG, 0, 0, 12, 8, 13, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_MATH_TRIG_HYP, 0, 6, 12, 13, 8, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_MATH_TRIG_HYP, 0, 6, 12, 8, 13, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_MATH_TRIG_HYP, FLAG_SCALAR_RHS, 6, 12, 8, 13, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_MATH_TRIG_HYP, 0, 0, 12, 8, 13, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_MATH_ACT, 0, 1, 12, 13, 8, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_MATH_ACT, 0, 1, 12, 8, 13, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_MATH_ACT, FLAG_SCALAR_RHS, 1, 12, 8, 13, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_MATH_ACT, 0, 0, 12, 8, 13, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_INT_BITWISE, 0, 0, 12, 13, 8, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_INT_BITWISE, 0, 0, 12, 8, 13, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_INT_BITWISE, 0, 4, 12, 8, 13, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_INT_BITWISE, FLAG_SCALAR_RHS, 0, 12, 8, 13, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_INT_BITWISE, 0, 3, 12, 8, 13, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_TERNARY, 0, 0, 12, 13, 8, 9, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_TERNARY, 0, 0, 12, 8, 13, 9, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_TERNARY, 0, 0, 12, 8, 9, 13, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_TERNARY, 0, 2, 12, 8, 9, 13, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_TERNARY, 0, 4, 12, 8, 9, 13, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_TERNARY, 0, 6, 12, 8, 9, 13, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_TERNARY, FLAG_SCALAR_RHS, 0, 12, 8, 13, 9, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_TERNARY, 0, 1, 12, 8, 9, 13, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_BLEND, 0, 0, 12, 13, 8, 9, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_BLEND, 0, 0, 12, 8, 13, 9, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_BLEND, 0, 0, 12, 8, 9, 13, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_BLEND, 0, 2, 12, 8, 9, 13, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_BLEND, FLAG_SCALAR_RHS, 0, 12, 8, 13, 9, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_BLEND, 0, 1, 12, 8, 9, 13, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VECTOR_RERANK, 0, 4, 12, 8, 9, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VECTOR_RERANK, 0, 4, 12, 13, 8, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VECTOR_RERANK, 0, 4, 12, 8, 13, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_VEC_POINT, 0, 2, 12, 8, 0, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_VEC_POINT, 0, 2, 12, 13, 0, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_MORSEL_COMMIT, 0, 0, 1, 13, 0, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_MORSEL_COMMIT, 0, 2, 1, 9, 13, 0, 0), IMPULSE_VM_ERR_VALIDATION_FAILED);
        checkMorselBody(enc(OP_MORSEL_COMMIT, FLAG_SCALAR_RHS, 2, 1, 9, 13, 0, 0), IMPULSE_VM_OK);
        checkMorselBody(enc(OP_MORSEL_COMMIT, 0, 8, 1, 13, 14, 0, 0), IMPULSE_VM_OK);
    }

    @Test
    void interpreterExecuteAndVectorHelpersCoverage() {
        V11Workspace ws = new V11Workspace(64);
        long[] prog = {
            enc(OP_LOAD_CONST, 0, 0, 0, 0, 0, 0, 7),
            enc(OP_LOAD_CONST, 0, 0, 1, 0, 0, 0, 35),
            enc(OP_SCALAR_INT_ALU, 0, 0, 2, 0, 1, 0, 0),
            enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0)
        };
        assertEquals(IMPULSE_VM_OK, V11Interpreter.execute(prog, new V11Interpreter.SnapshotBinding(new int[0]), ws));
        assertEquals(42L, ws.rRegs[2]);

        // V11Vector shallowCopy & LANE_STATE flat/nested accessors
        V11Arena arena = ws.arena;
        V11Vector vi = arena.allocI64(new long[]{10L, 20L}, 2, arena.allocValidAllTrue(2));
        V11Vector vc = arena.shallowCopyVec(vi);
        assertEquals(2, vc.length);
        assertEquals(20L, vc.i64Data[1]);
        assertTrue(vc.isValidAt(1));
        assertFalse(vc.isValidAt(5));

        V11Vector ls = arena.allocVec();
        ls.elemTag = V11Vector.TAG_LANE_STATE;
        ls.length = 2;
        ls.lsK = 2;
        ls.lsValsFlat = new double[]{1.0, 2.0, 3.0, 4.0};
        ls.lsValidFlat = new byte[]{1, 0, 1, 1};
        ls.lsWitnessFlat = new long[]{11L, 0L, 33L, 44L};
        ls.lsWitnessValidFlat = new byte[]{1, 0, 1, 0};
        assertTrue(ls.lsIsValid(0, 0));
        assertFalse(ls.lsIsValid(0, 1));
        assertEquals(3.0, ls.lsGetVal(1, 0));
        assertTrue(ls.lsHasWitness());
        assertTrue(ls.lsIsWitnessValid(1, 0));
        assertFalse(ls.lsIsWitnessValid(1, 1));
        assertEquals(33L, ls.lsGetWitness(1, 0));
        V11Vector lsCopy = ls.shallowCopy();
        assertEquals(4.0, lsCopy.lsGetVal(1, 1));
    }
}
