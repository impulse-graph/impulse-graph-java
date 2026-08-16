package org.impulsegraph.vm;

import java.lang.foreign.MemoryLayout;

import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.*;

/**
 * Java 25 FFM MemoryLayout definitions and VarHandle accessors for Impulse VM structs.
 * Maps 1:1 with the 640-byte C-ABI struct impulse_vm_state_t and 8-byte impulse_instruction_t.
 */
public final class VmStateLayout {

    private VmStateLayout() {}

    /**
     * 8-byte fixed instruction layout:
     * - opcode: byte (offset 0)
     * - flags: byte (offset 1)
     * - dst_reg: short (offset 2..3)
     * - payload: int (offset 4..7)
     */
    public static final StructLayout INSTRUCTION_LAYOUT = MemoryLayout.structLayout(
            JAVA_BYTE.withName("opcode"),
            JAVA_BYTE.withName("flags"),
            JAVA_SHORT.withName("dst_reg"),
            JAVA_INT.withName("payload")
    );

    public static final VarHandle INSTR_OPCODE_HANDLE =
            INSTRUCTION_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("opcode"));
    public static final VarHandle INSTR_FLAGS_HANDLE =
            INSTRUCTION_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("flags"));
    public static final VarHandle INSTR_DST_REG_HANDLE =
            INSTRUCTION_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("dst_reg"));
    public static final VarHandle INSTR_PAYLOAD_HANDLE =
            INSTRUCTION_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("payload"));

    /**
     * 640-byte VM State layout matching impulse_vm_state_t:
     * - pc: uint32 (offset 0)
     * - reserved: uint32 (offset 4)
     * - flags: uint64 (offset 8)
     * - registers: uint64[64] (offset 16..527)
     * - register_types: uint8[64] (offset 528..591)
     * - query_context: pointer (offset 592..599)
     * - call_stack: uint32[8] (offset 600..631)
     * - call_stack_depth: uint32 (offset 632..635)
     * - reserved_padding2: uint32 (offset 636..639)
     */
    public static final StructLayout VM_STATE_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("pc"),
            JAVA_INT.withName("reserved"),
            JAVA_LONG.withName("flags"),
            MemoryLayout.sequenceLayout(64, JAVA_LONG).withName("registers"),
            MemoryLayout.sequenceLayout(64, JAVA_BYTE).withName("register_types"),
            ADDRESS.withName("query_context"),
            MemoryLayout.sequenceLayout(8, JAVA_INT).withName("call_stack"),
            JAVA_INT.withName("call_stack_depth"),
            JAVA_INT.withName("reserved_padding2")
    );

    public static final VarHandle PC_HANDLE =
            VM_STATE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("pc"));
    public static final VarHandle FLAGS_HANDLE =
            VM_STATE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("flags"));
    public static final VarHandle REGISTER_ELEMENT_HANDLE =
            VM_STATE_LAYOUT.varHandle(
                    MemoryLayout.PathElement.groupElement("registers"),
                    MemoryLayout.PathElement.sequenceElement()
            );
    public static final VarHandle REGISTER_TYPE_ELEMENT_HANDLE =
            VM_STATE_LAYOUT.varHandle(
                    MemoryLayout.PathElement.groupElement("register_types"),
                    MemoryLayout.PathElement.sequenceElement()
            );
    public static final VarHandle CALL_STACK_ELEMENT_HANDLE =
            VM_STATE_LAYOUT.varHandle(
                    MemoryLayout.PathElement.groupElement("call_stack"),
                    MemoryLayout.PathElement.sequenceElement()
            );
    public static final VarHandle CALL_STACK_DEPTH_HANDLE =
            VM_STATE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("call_stack_depth"));

    public static final long STATE_SIZE_BYTES = VM_STATE_LAYOUT.byteSize();
    public static final long INSTRUCTION_SIZE_BYTES = INSTRUCTION_LAYOUT.byteSize();
}
