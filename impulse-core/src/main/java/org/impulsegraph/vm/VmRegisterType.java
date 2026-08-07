package org.impulsegraph.vm;

/**
 * Impulse VM Register Types, Opcodes, and Flag Bitmask Constants.
 * Strictly aligned with C++ header impulse_vm.h.
 */
public final class VmRegisterType {

    private VmRegisterType() {}

    // Magic constant
    public static final int IMPULSE_VM_MAGIC = 0x494D5042; // 'IMPB'

    // Status Flags Bitmasks (FLAGS Register)
    public static final long FLAG_ZF = 1L << 0; // Zero Flag
    public static final long FLAG_LT = 1L << 1; // Less Than Flag
    public static final long FLAG_GT = 1L << 2; // Greater Than Flag
    public static final long FLAG_EQ = 1L << 3; // Equal Flag
    public static final long FLAG_ST = 1L << 4; // Stable Flag

    // Opcode Modifier Flags (FLAGS field in instruction)
    public static final byte OP_FLAG_MODE_BITSET = 0x01;
    public static final byte OP_FLAG_ACCUMULATE  = 0x02;
    public static final byte OP_FLAG_INVERT      = 0x04;
    public static final byte OP_FLAG_OFFHEAP     = 0x08;

    // Register Type Tags
    public static final byte TYPE_NULL           = 0x00;
    public static final byte TYPE_INT64          = 0x01;
    public static final byte TYPE_NODE_ID        = 0x02;
    public static final byte TYPE_RELATION_ID    = 0x03;
    public static final byte TYPE_BITSET_HANDLE  = 0x04;
    public static final byte TYPE_NODE_VECTOR    = 0x05;
    public static final byte TYPE_CSR_SPAN       = 0x06;
    public static final byte TYPE_BOOLEAN        = 0x07;
    public static final byte TYPE_FLOAT          = 0x08;
    public static final byte TYPE_DOUBLE         = 0x09;
    public static final byte TYPE_VALUE_MAP      = 0x0A;
    public static final byte TYPE_STRING_VECTOR   = 0x0B;
    public static final byte TYPE_FLOAT_VECTOR    = 0x0C;
    public static final byte TYPE_DOUBLE_VECTOR   = 0x0D;
    public static final byte TYPE_UINT64_VECTOR   = 0x0E;

    // Opcodes Definitions
    public static final byte OP_NOP                    = (byte) 0x00;
    public static final byte OP_INIT_INPUT_NODE        = (byte) 0x01;
    public static final byte OP_INIT_INPUT_SET         = (byte) 0x02;
    public static final byte OP_LOAD_CONST_INT         = (byte) 0x03;
    public static final byte OP_MAP_KEYS_TO_DENSE      = (byte) 0x04;
    public static final byte OP_LOAD_CONST_FLOAT       = (byte) 0x05;
    public static final byte OP_LOAD_CONST_STR_PREFIX  = (byte) 0x06;

    public static final byte OP_CSR_WALK               = (byte) 0x10;
    public static final byte OP_CSR_WALK_FILTERED      = (byte) 0x11;
    public static final byte OP_CSR_DEGREE             = (byte) 0x12;
    public static final byte OP_CSR_WALK_PREDICATE     = (byte) 0x13;
    public static final byte OP_NODE_FILTER            = (byte) 0x14;
    public static final byte OP_NODE_FILTER_STR_PREFIX = (byte) 0x15;
    public static final byte OP_CSR_WALK_REDUCE_SUM    = (byte) 0x16;
    public static final byte OP_CSR_WALK_REDUCE        = (byte) 0x17;
    public static final byte OP_CSC_WALK               = (byte) 0x18;

    public static final byte OP_SET_UNION              = (byte) 0x30;
    public static final byte OP_SET_INTERSECT          = (byte) 0x31;
    public static final byte OP_SET_DIFFERENCE         = (byte) 0x32;
    public static final byte OP_SET_CARDINALITY        = (byte) 0x33;
    public static final byte OP_VECTOR_MUL_ATTR        = (byte) 0x34;
    public static final byte OP_VECTOR_REDUCE_SUM      = (byte) 0x35;
    public static final byte OP_VECTOR_DIV             = (byte) 0x36;
    public static final byte OP_VECTOR_STR_CONCAT      = (byte) 0x37;
    public static final byte OP_CC_AFFOREST            = (byte) 0x40;
    public static final byte OP_MXV                    = (byte) 0x41;
    public static final byte OP_VXM                    = (byte) 0x42;
    public static final byte OP_EWISE_ADD              = (byte) 0x43;
    public static final byte OP_EWISE_MULT             = (byte) 0x44;
    public static final byte OP_REDUCE                 = (byte) 0x45;

    public static final byte OP_JMP                    = (byte) 0x50;
    public static final byte OP_JZ                     = (byte) 0x51;
    public static final byte OP_JNZ                    = (byte) 0x52;
    public static final byte OP_LOOP_DECR              = (byte) 0x53;
    public static final byte OP_STABLE_CHECK           = (byte) 0x54;
    public static final byte OP_CALL                   = (byte) 0x55;
    public static final byte OP_RET                    = (byte) 0x56;

    public static final byte OP_MOV                    = (byte) 0x70;
    public static final byte OP_CLEAR_REG              = (byte) 0x71;

    public static final byte OP_COLLECT_BITSET         = (byte) 0x90;
    public static final byte OP_COLLECT_ARRAY          = (byte) 0x91;
    public static final byte OP_MAP_DENSE_TO_KEYS      = (byte) 0x92;
    public static final byte OP_COLLECT_VALUE_MAP      = (byte) 0x93;
    public static final byte OP_HALT                   = (byte) 0xFF;

    // GraphBLAS Semiring IDs
    public static final int SEMIRING_PLUS_TIMES       = 0;
    public static final int SEMIRING_MIN_PLUS         = 1;
    public static final int SEMIRING_MAX_MIN          = 2;
    public static final int SEMIRING_BOOL             = 3;

    // GraphBLAS Binary / Monoid Operator IDs
    public static final int BINARY_OP_ADD             = 0;
    public static final int BINARY_OP_MUL             = 1;
    public static final int BINARY_OP_MIN             = 2;
    public static final int BINARY_OP_MAX             = 3;
    public static final int BINARY_OP_AND             = 4;
    public static final int BINARY_OP_OR              = 5;

    // VM Execution status codes
    public static final int VM_OK                      = 0;
    public static final int VM_ERR_INVALID_OPCODE      = 1;
    public static final int VM_ERR_OUT_OF_BOUNDS       = 2;
    public static final int VM_ERR_NULL_SNAPSHOT       = 3;
    public static final int VM_ERR_STACK_OVERFLOW      = 4;
    public static final int VM_ERR_STACK_UNDERFLOW     = 5;
    public static final int VM_ERR_INVALID_REGISTER    = 6;
}
