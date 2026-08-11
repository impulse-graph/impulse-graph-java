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
    public static final byte OP_HALT                   = (byte) 0x00;
    public static final byte OP_NOP                    = (byte) 0x01;
    public static final byte OP_INIT_INPUT_NODE        = (byte) 0x02;
    public static final byte OP_INIT_INPUT_SET         = (byte) 0x03;
    public static final byte OP_LOAD_CONST_INT         = (byte) 0x04;
    public static final byte OP_MAP_KEYS_TO_DENSE      = (byte) 0x05;
    public static final byte OP_LOAD_CONST_FLOAT       = (byte) 0x06;
    public static final byte OP_LOAD_CONST_STR_PREFIX  = (byte) 0x07;
    public static final byte OP_LOAD_INLINE_ARRAY      = (byte) 0x08;
    public static final byte OP_INIT_MOCK_GRAPH        = (byte) 0x09;

    public static final byte OP_RESERVED_0A            = (byte) 0x0A;
    public static final byte OP_RESERVED_0B            = (byte) 0x0B;
    public static final byte OP_RESERVED_0C            = (byte) 0x0C;
    public static final byte OP_RESERVED_0D            = (byte) 0x0D;
    public static final byte OP_RESERVED_0E            = (byte) 0x0E;
    public static final byte OP_RESERVED_0F            = (byte) 0x0F;

    public static final byte OP_CSR_WALK               = (byte) 0x10;
    public static final byte OP_CSR_WALK_FILTERED      = (byte) 0x11;
    public static final byte OP_CSR_DEGREE             = (byte) 0x12;
    public static final byte OP_CSR_WALK_PREDICATE     = (byte) 0x13;
    public static final byte OP_NODE_FILTER            = (byte) 0x14;
    public static final byte OP_NODE_FILTER_STR_PREFIX = (byte) 0x15;
    public static final byte OP_CSR_WALK_REDUCE_SUM    = (byte) 0x16;
    public static final byte OP_CSR_WALK_REDUCE        = (byte) 0x17;
    public static final byte OP_CSC_WALK               = (byte) 0x18;

    public static final byte OP_RESERVED_1D            = (byte) 0x1D;
    public static final byte OP_RESERVED_1E            = (byte) 0x1E;
    public static final byte OP_RESERVED_1F            = (byte) 0x1F;
    public static final byte OP_RESERVED_20            = (byte) 0x20;
    public static final byte OP_RESERVED_21            = (byte) 0x21;
    public static final byte OP_RESERVED_22            = (byte) 0x22;
    public static final byte OP_RESERVED_23            = (byte) 0x23;
    public static final byte OP_RESERVED_24            = (byte) 0x24;
    public static final byte OP_RESERVED_25            = (byte) 0x25;
    public static final byte OP_RESERVED_26            = (byte) 0x26;
    public static final byte OP_RESERVED_27            = (byte) 0x27;
    public static final byte OP_RESERVED_28            = (byte) 0x28;
    public static final byte OP_RESERVED_29            = (byte) 0x29;
    public static final byte OP_RESERVED_2A            = (byte) 0x2A;
    public static final byte OP_RESERVED_2B            = (byte) 0x2B;
    public static final byte OP_RESERVED_2C            = (byte) 0x2C;
    public static final byte OP_RESERVED_2D            = (byte) 0x2D;
    public static final byte OP_RESERVED_2E            = (byte) 0x2E;
    public static final byte OP_RESERVED_2F            = (byte) 0x2F;

    public static final byte OP_SET_UNION              = (byte) 0x30;
    public static final byte OP_SET_INTERSECT          = (byte) 0x31;
    public static final byte OP_SET_DIFFERENCE         = (byte) 0x32;
    public static final byte OP_SET_CARDINALITY        = (byte) 0x33;
    public static final byte OP_VECTOR_MUL_ATTR        = (byte) 0x34;
    public static final byte OP_VECTOR_REDUCE_SUM      = (byte) 0x35;
    public static final byte OP_VECTOR_DIV             = (byte) 0x36;
    public static final byte OP_VECTOR_STR_CONCAT      = (byte) 0x37;
    public static final byte OP_FLOAT_VECTOR_SCALE     = (byte) 0x38;
    public static final byte OP_L1_NORM_DIFF           = (byte) 0x39;

    public static final byte OP_RESERVED_3A            = (byte) 0x3A;
    public static final byte OP_RESERVED_3B            = (byte) 0x3B;
    public static final byte OP_RESERVED_3C            = (byte) 0x3C;
    public static final byte OP_RESERVED_3D            = (byte) 0x3D;
    public static final byte OP_RESERVED_3E            = (byte) 0x3E;
    public static final byte OP_RESERVED_3F            = (byte) 0x3F;

    public static final byte OP_CC_AFFOREST            = (byte) 0x40;
    public static final byte OP_MXV                    = (byte) 0x41;
    public static final byte OP_VXM                    = (byte) 0x42;
    public static final byte OP_EWISE_ADD              = (byte) 0x43;
    public static final byte OP_EWISE_MULT             = (byte) 0x44;
    public static final byte OP_REDUCE                 = (byte) 0x45;
    public static final byte OP_CC_HOOK_COMPRESS       = (byte) 0x46;
    public static final byte OP_TC_SWEEP_BATCH         = (byte) 0x47;
    public static final byte OP_BRANDES_FORWARD        = (byte) 0x48;
    public static final byte OP_BRANDES_BACKWARD       = (byte) 0x49;
    public static final byte OP_DELTA_STEP_RELAX       = (byte) 0x4A;
    public static final byte OP_READ_EDGE_WEIGHT       = (byte) 0x4B;

    public static final byte OP_RESERVED_4C            = (byte) 0x4C;
    public static final byte OP_RESERVED_4D            = (byte) 0x4D;
    public static final byte OP_RESERVED_4E            = (byte) 0x4E;
    public static final byte OP_RESERVED_4F            = (byte) 0x4F;

    public static final byte OP_JMP                    = (byte) 0x50;
    public static final byte OP_JZ                     = (byte) 0x51;
    public static final byte OP_JNZ                    = (byte) 0x52;
    public static final byte OP_LOOP_DECR              = (byte) 0x53;
    public static final byte OP_STABLE_CHECK           = (byte) 0x54;
    public static final byte OP_CALL                   = (byte) 0x55;
    public static final byte OP_RET                    = (byte) 0x56;

    public static final byte OP_ENTER_FRAME            = (byte) 0x57;
    public static final byte OP_LEAVE_FRAME            = (byte) 0x58;
    public static final byte OP_RESERVED_59            = (byte) 0x59;

    public static final byte OP_THROW                  = (byte) 0x5A;
    public static final byte OP_ASSERT                 = (byte) 0x5B;
    public static final byte OP_TRAP                   = (byte) 0x5C;

    public static final byte OP_RESERVED_5D            = (byte) 0x5D;
    public static final byte OP_RESERVED_5E            = (byte) 0x5E;
    public static final byte OP_RESERVED_5F            = (byte) 0x5F;

    // Extended Domain Opcodes (0x60 - 0x6A)
    public static final byte OP_SAMPLE_NEIGHBORS       = (byte) 0x60;
    public static final byte OP_RANDOM_WALK            = (byte) 0x61;
    public static final byte OP_SCATTER_GATHER         = (byte) 0x62;
    public static final byte OP_REBAC_CHECK            = (byte) 0x63;
    public static final byte OP_ROARING_BITMAP_AND     = (byte) 0x64;
    public static final byte OP_ISLAND_DETECT          = (byte) 0x65;
    public static final byte OP_SPARSE_MATVEC          = (byte) 0x66;
    public static final byte OP_LOUVAIN_MODULARITY     = (byte) 0x67;
    public static final byte OP_KCORE_DECOMPOSITION    = (byte) 0x68;
    public static final byte OP_MOTIF_MATCH_3          = (byte) 0x69;
    public static final byte OP_GRAPH_ISOMORPHISM      = (byte) 0x6A;
    public static final byte OP_ROARING_BITMAP_OR      = (byte) 0x6B;
    public static final byte OP_ROARING_BITMAP_AND_NOT = (byte) 0x6C;

    public static final byte OP_RESERVED_6D            = (byte) 0x6D;
    public static final byte OP_RESERVED_6E            = (byte) 0x6E;
    public static final byte OP_RESERVED_6F            = (byte) 0x6F;

    public static final byte OP_MOV                    = (byte) 0x70;
    public static final byte OP_CLEAR_REG              = (byte) 0x71;
    public static final byte OP_LOAD_INDIRECT          = (byte) 0x72;
    public static final byte OP_ALLOC_SCRATCH          = (byte) 0x73;
    public static final byte OP_ASSERT_SCRATCH_BYTES   = (byte) 0x74;
    public static final byte OP_SET_MAX_DOP            = (byte) 0x75;

    public static final byte OP_RESERVED_76            = (byte) 0x76;
    public static final byte OP_RESERVED_77            = (byte) 0x77;
    public static final byte OP_RESERVED_78            = (byte) 0x78;
    public static final byte OP_RESERVED_79            = (byte) 0x79;
    public static final byte OP_RESERVED_7A            = (byte) 0x7A;
    public static final byte OP_RESERVED_7B            = (byte) 0x7B;
    public static final byte OP_RESERVED_7C            = (byte) 0x7C;
    public static final byte OP_RESERVED_7D            = (byte) 0x7D;
    public static final byte OP_RESERVED_7E            = (byte) 0x7E;
    public static final byte OP_RESERVED_7F            = (byte) 0x7F;
    public static final byte OP_RESERVED_80            = (byte) 0x80;
    public static final byte OP_RESERVED_81            = (byte) 0x81;
    public static final byte OP_RESERVED_82            = (byte) 0x82;
    public static final byte OP_RESERVED_83            = (byte) 0x83;
    public static final byte OP_RESERVED_84            = (byte) 0x84;
    public static final byte OP_RESERVED_85            = (byte) 0x85;
    public static final byte OP_RESERVED_86            = (byte) 0x86;
    public static final byte OP_RESERVED_87            = (byte) 0x87;
    public static final byte OP_RESERVED_88            = (byte) 0x88;
    public static final byte OP_RESERVED_89            = (byte) 0x89;
    public static final byte OP_RESERVED_8A            = (byte) 0x8A;
    public static final byte OP_RESERVED_8B            = (byte) 0x8B;
    public static final byte OP_RESERVED_8C            = (byte) 0x8C;
    public static final byte OP_RESERVED_8D            = (byte) 0x8D;
    public static final byte OP_RESERVED_8E            = (byte) 0x8E;
    public static final byte OP_RESERVED_8F            = (byte) 0x8F;

    public static final byte OP_COLLECT_BITSET         = (byte) 0x90;
    public static final byte OP_COLLECT_ARRAY          = (byte) 0x91;
    public static final byte OP_MAP_DENSE_TO_KEYS      = (byte) 0x92;
    public static final byte OP_COLLECT_VALUE_MAP      = (byte) 0x93;

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
    public static final int VM_ERR_USER_THROW          = 7;
    public static final int VM_ERR_ASSERTION_FAILED    = 8;
    public static final int VM_ERR_TRAP                = 9;
}
