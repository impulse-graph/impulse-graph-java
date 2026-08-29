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
    public static final byte OP_FLAG_EXTENDED    = (byte) 0x80; // 128-bit Extended Instruction format
    public static final byte OP_EXTENSION_PAYLOAD = (byte) 0xFF; // Tombstone marker for 2nd 64-bit word

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
    public static final byte TYPE_FRONTIER_STATE  = 0x0F;

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
    public static final byte OP_CSR_WALK_2HOP          = (byte) 0x0E;
    public static final byte OP_CSR_WALK_STATE         = (byte) 0x0F;

    public static final byte OP_CSR_WALK               = (byte) 0x10;
    public static final byte OP_CSR_WALK_FILTERED      = (byte) 0x11;
    public static final byte OP_CSR_DEGREE             = (byte) 0x12;
    public static final byte OP_CSR_WALK_PREDICATE     = (byte) 0x13;
    public static final byte OP_NODE_FILTER            = (byte) 0x14;
    public static final byte OP_NODE_FILTER_STR_PREFIX = (byte) 0x15;
    public static final byte OP_CSR_WALK_REDUCE_SUM    = (byte) 0x16;
    public static final byte OP_CSR_WALK_REDUCE        = (byte) 0x17;
    public static final byte OP_CSC_WALK               = (byte) 0x18;
    public static final byte OP_HAS_CSR                = (byte) 0x19;
    public static final byte OP_HAS_CSC                = (byte) 0x1A;
    public static final byte OP_HAS_COO                = (byte) 0x1B;
    public static final byte OP_HAS_KEY_CATALOG        = (byte) 0x1C;
    public static final byte OP_DENSE_WALK_LEGACY      = (byte) 0x1D;
    public static final byte OP_ADAPTIVE_WALK          = (byte) 0x1D;
    public static final byte OP_CREATE_SCRATCH_INDEX   = (byte) 0x1E;
    public static final byte OP_DROP_SCRATCH_INDEX     = (byte) 0x1F;

    // Vector Predicate and SIMD Mask Opcodes (0x20 - 0x2F)
    public static final byte OP_VEC_CMP_EQ             = (byte) 0x20;
    public static final byte OP_VEC_CMP_GT             = (byte) 0x21;
    public static final byte OP_VEC_CMP_LT             = (byte) 0x22;
    public static final byte OP_VEC_CMP_BETWEEN        = (byte) 0x23;
    public static final byte OP_MASK_AND               = (byte) 0x24;
    public static final byte OP_MASK_OR                = (byte) 0x25;
    public static final byte OP_MASK_NOT               = (byte) 0x26;
    public static final byte OP_VEC_BLEND              = (byte) 0x27;
    public static final byte OP_RESERVED_28            = (byte) 0x28;
    public static final byte OP_RESERVED_29            = (byte) 0x29;
    public static final byte OP_ASSERT_FINITE          = (byte) 0x2A;
    public static final byte OP_RESERVED_2B            = (byte) 0x2B;
    public static final byte OP_RESERVED_2C            = (byte) 0x2C;
    public static final byte OP_VEC_MATH_UNARY         = (byte) 0x2D;
    public static final byte OP_VEC_MATH_BINARY        = (byte) 0x2E;
    public static final byte OP_VEC_MATH_TERNARY       = (byte) 0x2F;

    public static final byte OP_SET_UNION              = (byte) 0x30;
    public static final byte OP_SET_INTERSECT          = (byte) 0x31;
    public static final byte OP_SET_DIFFERENCE         = (byte) 0x32;
    public static final byte OP_SET_CARDINALITY        = (byte) 0x33;
    public static final byte OP_VECTOR_LOAD_ATTR        = (byte) 0x34;
    public static final byte OP_VECTOR_REDUCE_SUM      = (byte) 0x35;
    public static final byte OP_VECTOR_DIV             = (byte) 0x36;
    public static final byte OP_VECTOR_STR_CONCAT      = (byte) 0x37;
    public static final byte OP_FLOAT_VECTOR_SCALE     = (byte) 0x38;
    public static final byte OP_L1_NORM_DIFF           = (byte) 0x39;

    public static final byte OP_PROJECT_STATE          = (byte) 0x3A;
    public static final byte OP_RESERVED_3B            = (byte) 0x3B;
    public static final byte OP_RESERVED_3C            = (byte) 0x3C;
    public static final byte OP_VECTOR_TIME_VALID_AT   = (byte) 0x3D;
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
    public static final byte OP_LOAD_COLUMN_VECTOR     = (byte) 0x80;
    public static final byte OP_GATHER_NODE_ATTR       = (byte) 0x81;
    public static final byte OP_GATHER_EDGE_ATTR       = (byte) 0x82;
    public static final byte OP_BRIN_ZONE_SKIP         = (byte) 0x83;
    public static final byte OP_CSR_WALK_DIRECT_STORE  = (byte) 0x84;
    public static final byte OP_CSR_WALK_DENSE_STREAM  = (byte) 0x85;
    public static final byte OP_COO_WALK               = (byte) 0x86;
    public static final byte OP_CSC_WALK_DIRECT_STORE  = (byte) 0x87;
    public static final byte OP_FIXPOINT_KLEENE_STAR   = (byte) 0x88;
    public static final byte OP_SWAP_REG               = (byte) 0x89;
    public static final byte OP_FRONTIER_DIFF          = (byte) 0x8A;
    public static final byte OP_COO_WALK_FILTERED      = (byte) 0x8B;
    public static final byte OP_COO_WALK_REDUCE        = (byte) 0x8C;
    public static final byte OP_COO_WALK_DIRECT_STORE  = (byte) 0x8D;
    public static final byte OP_DENSE_WALK             = (byte) 0x8E;
    public static final byte OP_DENSE_WALK_BITMATRIX   = (byte) 0x8F;

    public static final byte OP_COLLECT_BITSET         = (byte) 0x90;
    public static final byte OP_COLLECT_ARRAY          = (byte) 0x91;
    public static final byte OP_MAP_DENSE_TO_KEYS      = (byte) 0x92;
    public static final byte OP_COLLECT_VALUE_MAP      = (byte) 0x93;
    public static final byte OP_DENSE_WALK_REDUCE      = (byte) 0x94;
    public static final byte OP_DENSE_WALK_DIRECT_STORE= (byte) 0x95;

    // Edge Stream Shader Opcodes (0xA0 - 0xBD)
    public static final byte OP_COO_WALK_STREAM        = (byte) 0xA0;
    public static final byte OP_STREAM_FUNC_BEGIN      = (byte) 0xA1;
    public static final byte OP_STREAM_FUNC_END        = (byte) 0xA2;
    public static final byte OP_STREAM_LOAD_SRC        = (byte) 0xA3;
    public static final byte OP_STREAM_LOAD_EDGE       = (byte) 0xA4;
    public static final byte OP_STREAM_MATH_ADD        = (byte) 0xA5;
    public static final byte OP_STREAM_MATH_DIV        = (byte) 0xA6;
    public static final byte OP_STREAM_FILTER          = (byte) 0xA7;
    public static final byte OP_STREAM_REDUCE          = (byte) 0xA8;
    public static final byte OP_CSR_WALK_STREAM        = (byte) 0xA9;
    public static final byte OP_CSC_WALK_STREAM        = (byte) 0xAA;
    public static final byte OP_STREAM_LOAD_TGT        = (byte) 0xAB;
    public static final byte OP_STREAM_MATH_SUB        = (byte) 0xAC;
    public static final byte OP_STREAM_MATH_MUL        = (byte) 0xAD;
    public static final byte OP_STREAM_MATH_MOD        = (byte) 0xAE;
    public static final byte OP_STREAM_MATH_UNARY      = (byte) 0xAF;
    public static final byte OP_STREAM_CMP_EQ          = (byte) 0xB0;
    public static final byte OP_STREAM_CMP_NEQ         = (byte) 0xB1;
    public static final byte OP_STREAM_CMP_GT          = (byte) 0xB2;
    public static final byte OP_STREAM_CMP_LT          = (byte) 0xB3;
    public static final byte OP_STREAM_LOGIC_AND       = (byte) 0xB4;
    public static final byte OP_STREAM_LOGIC_OR        = (byte) 0xB5;
    public static final byte OP_STREAM_LOGIC_NOT       = (byte) 0xB6;
    public static final byte OP_STREAM_SELECT          = (byte) 0xB7;
    public static final byte OP_STREAM_REDUCE_ARGMIN   = (byte) 0xB8;
    public static final byte OP_STREAM_REDUCE_ARGMAX   = (byte) 0xB9;
    public static final byte OP_STREAM_LOAD_SRC_ID     = (byte) 0xBA;
    public static final byte OP_STREAM_LOAD_TGT_ID     = (byte) 0xBB;
    public static final byte OP_STREAM_LOAD_EDGE_ID    = (byte) 0xBC;
    public static final byte OP_STREAM_LOAD_CONST      = (byte) 0xBD;

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
