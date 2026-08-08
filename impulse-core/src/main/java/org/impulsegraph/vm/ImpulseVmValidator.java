package org.impulsegraph.vm;

import static org.impulsegraph.vm.VmRegisterType.*;

/**
 * Java 25 Ahead-of-Time Bytecode Type & Register Validator for ImpulseVM.
 * Performs single-pass abstract type propagation across registers R0..R63.
 * Eliminates dynamic type tag branch mispredictions in SIMD execution loops.
 */
public final class ImpulseVmValidator {

    private ImpulseVmValidator() {}

    public static String validate(VmHandlers.Instruction[] program) {
        if (program == null || program.length == 0) {
            return "IMPULSE_VM_OK";
        }

        byte[] abstractTypes = new byte[64];

        for (VmHandlers.Instruction inst : program) {
            int opcode = Byte.toUnsignedInt(inst.opcode());
            int dst = inst.dstReg();
            int src = (inst.payload() >> 16) != 0 ? ((inst.payload() >> 16) & 0xFFFF) : (inst.payload() & 0xFFFF);

            if (dst >= 64) {
                return "IMPULSE_VM_ERR_INVALID_REGISTER";
            }

            switch (opcode) {
                case 0x70 -> { // OP_MOV
                    if (src >= 64) return "IMPULSE_VM_ERR_INVALID_REGISTER";
                    abstractTypes[dst] = abstractTypes[src];
                }
                case 0x01 -> abstractTypes[dst] = TYPE_NODE_ID;
                case 0x02, 0x04, 0x10, 0x11, 0x18, 0x30, 0x31, 0x32 -> abstractTypes[dst] = TYPE_BITSET_HANDLE;
                case 0x03, 0x12, 0x33 -> abstractTypes[dst] = TYPE_INT64;
                case 0x05, 0x16, 0x35 -> abstractTypes[dst] = TYPE_FLOAT;
                case 0x07 -> abstractTypes[dst] = TYPE_FLOAT_VECTOR;
                case 0x71 -> abstractTypes[dst] = TYPE_NULL;
                default -> {}
            }
        }
        return "IMPULSE_VM_OK";
    }
}
