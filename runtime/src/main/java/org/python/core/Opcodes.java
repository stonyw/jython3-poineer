package org.python.core;

/**
 * CPython 3.14 opcode numbers: the instruction set this VM executes.
 *
 * Generated, not transcribed —
 *
 * <pre>
 *   runtime/tools/gen_opcodes.py &gt; this file
 *   runtime/tools/gen_opcodes.py --check this file
 * </pre>
 *
 * The constants cover every number a {@code .pyc} can carry, which is the <em>unspecialized</em>
 * set: an adapted opcode ({@code BINARY_OP_ADD_INT} and its relations) exists only inside a running
 * interpreter's private copy of a code array and never in a stream {@code marshal} writes. One
 * reaching {@link Interpreter}'s dispatch loop is therefore a reader bug, and is reported as one
 * rather than executed as whatever base opcode its number resembles.
 */
public final class Opcodes {
    // Numbers, in opcode order.
    public static final int CACHE                            =   0;
    public static final int BINARY_SLICE                     =   1;
    public static final int BUILD_TEMPLATE                   =   2;
    public static final int CALL_FUNCTION_EX                 =   4;
    public static final int CHECK_EG_MATCH                   =   5;
    public static final int CHECK_EXC_MATCH                  =   6;
    public static final int CLEANUP_THROW                    =   7;
    public static final int DELETE_SUBSCR                    =   8;
    public static final int END_FOR                          =   9;
    public static final int END_SEND                         =  10;
    public static final int EXIT_INIT_CHECK                  =  11;
    public static final int FORMAT_SIMPLE                    =  12;
    public static final int FORMAT_WITH_SPEC                 =  13;
    public static final int GET_AITER                        =  14;
    public static final int GET_ANEXT                        =  15;
    public static final int GET_ITER                         =  16;
    public static final int RESERVED                         =  17;
    public static final int GET_LEN                          =  18;
    public static final int GET_YIELD_FROM_ITER              =  19;
    public static final int INTERPRETER_EXIT                 =  20;
    public static final int LOAD_BUILD_CLASS                 =  21;
    public static final int LOAD_LOCALS                      =  22;
    public static final int MAKE_FUNCTION                    =  23;
    public static final int MATCH_KEYS                       =  24;
    public static final int MATCH_MAPPING                    =  25;
    public static final int MATCH_SEQUENCE                   =  26;
    public static final int NOP                              =  27;
    public static final int NOT_TAKEN                        =  28;
    public static final int POP_EXCEPT                       =  29;
    public static final int POP_ITER                         =  30;
    public static final int POP_TOP                          =  31;
    public static final int PUSH_EXC_INFO                    =  32;
    public static final int PUSH_NULL                        =  33;
    public static final int RETURN_GENERATOR                 =  34;
    public static final int RETURN_VALUE                     =  35;
    public static final int SETUP_ANNOTATIONS                =  36;
    public static final int STORE_SLICE                      =  37;
    public static final int STORE_SUBSCR                     =  38;
    public static final int TO_BOOL                          =  39;
    public static final int UNARY_INVERT                     =  40;
    public static final int UNARY_NEGATIVE                   =  41;
    public static final int UNARY_NOT                        =  42;
    public static final int WITH_EXCEPT_START                =  43;
    public static final int BINARY_OP                        =  44;
    public static final int BUILD_INTERPOLATION              =  45;
    public static final int BUILD_LIST                       =  46;
    public static final int BUILD_MAP                        =  47;
    public static final int BUILD_SET                        =  48;
    public static final int BUILD_SLICE                      =  49;
    public static final int BUILD_STRING                     =  50;
    public static final int BUILD_TUPLE                      =  51;
    public static final int CALL                             =  52;
    public static final int CALL_INTRINSIC_1                 =  53;
    public static final int CALL_INTRINSIC_2                 =  54;
    public static final int CALL_KW                          =  55;
    public static final int COMPARE_OP                       =  56;
    public static final int CONTAINS_OP                      =  57;
    public static final int CONVERT_VALUE                    =  58;
    public static final int COPY                             =  59;
    public static final int COPY_FREE_VARS                   =  60;
    public static final int DELETE_ATTR                      =  61;
    public static final int DELETE_DEREF                     =  62;
    public static final int DELETE_FAST                      =  63;
    public static final int DELETE_GLOBAL                    =  64;
    public static final int DELETE_NAME                      =  65;
    public static final int DICT_MERGE                       =  66;
    public static final int DICT_UPDATE                      =  67;
    public static final int END_ASYNC_FOR                    =  68;
    public static final int EXTENDED_ARG                     =  69;
    public static final int FOR_ITER                         =  70;
    public static final int GET_AWAITABLE                    =  71;
    public static final int IMPORT_FROM                      =  72;
    public static final int IMPORT_NAME                      =  73;
    public static final int IS_OP                            =  74;
    public static final int JUMP_BACKWARD                    =  75;
    public static final int JUMP_BACKWARD_NO_INTERRUPT       =  76;
    public static final int JUMP_FORWARD                     =  77;
    public static final int LIST_APPEND                      =  78;
    public static final int LIST_EXTEND                      =  79;
    public static final int LOAD_ATTR                        =  80;
    public static final int LOAD_COMMON_CONSTANT             =  81;
    public static final int LOAD_CONST                       =  82;
    public static final int LOAD_DEREF                       =  83;
    public static final int LOAD_FAST                        =  84;
    public static final int LOAD_FAST_AND_CLEAR              =  85;
    public static final int LOAD_FAST_BORROW                 =  86;
    public static final int LOAD_FAST_BORROW_LOAD_FAST_BORROW =  87;
    public static final int LOAD_FAST_CHECK                  =  88;
    public static final int LOAD_FAST_LOAD_FAST              =  89;
    public static final int LOAD_FROM_DICT_OR_DEREF          =  90;
    public static final int LOAD_FROM_DICT_OR_GLOBALS        =  91;
    public static final int LOAD_GLOBAL                      =  92;
    public static final int LOAD_NAME                        =  93;
    public static final int LOAD_SMALL_INT                   =  94;
    public static final int LOAD_SPECIAL                     =  95;
    public static final int LOAD_SUPER_ATTR                  =  96;
    public static final int MAKE_CELL                        =  97;
    public static final int MAP_ADD                          =  98;
    public static final int MATCH_CLASS                      =  99;
    public static final int POP_JUMP_IF_FALSE                = 100;
    public static final int POP_JUMP_IF_NONE                 = 101;
    public static final int POP_JUMP_IF_NOT_NONE             = 102;
    public static final int POP_JUMP_IF_TRUE                 = 103;
    public static final int RAISE_VARARGS                    = 104;
    public static final int RERAISE                          = 105;
    public static final int SEND                             = 106;
    public static final int SET_ADD                          = 107;
    public static final int SET_FUNCTION_ATTRIBUTE           = 108;
    public static final int SET_UPDATE                       = 109;
    public static final int STORE_ATTR                       = 110;
    public static final int STORE_DEREF                      = 111;
    public static final int STORE_FAST                       = 112;
    public static final int STORE_FAST_LOAD_FAST             = 113;
    public static final int STORE_FAST_STORE_FAST            = 114;
    public static final int STORE_GLOBAL                     = 115;
    public static final int STORE_NAME                       = 116;
    public static final int SWAP                             = 117;
    public static final int UNPACK_EX                        = 118;
    public static final int UNPACK_SEQUENCE                  = 119;
    public static final int YIELD_VALUE                      = 120;
    public static final int RESUME                           = 128;
    public static final int INSTRUMENTED_END_FOR             = 234;
    public static final int INSTRUMENTED_POP_ITER            = 235;
    public static final int INSTRUMENTED_END_SEND            = 236;
    public static final int INSTRUMENTED_FOR_ITER            = 237;
    public static final int INSTRUMENTED_INSTRUCTION         = 238;
    public static final int INSTRUMENTED_JUMP_FORWARD        = 239;
    public static final int INSTRUMENTED_NOT_TAKEN           = 240;
    public static final int INSTRUMENTED_POP_JUMP_IF_TRUE    = 241;
    public static final int INSTRUMENTED_POP_JUMP_IF_FALSE   = 242;
    public static final int INSTRUMENTED_POP_JUMP_IF_NONE    = 243;
    public static final int INSTRUMENTED_POP_JUMP_IF_NOT_NONE = 244;
    public static final int INSTRUMENTED_RESUME              = 245;
    public static final int INSTRUMENTED_RETURN_VALUE        = 246;
    public static final int INSTRUMENTED_YIELD_VALUE         = 247;
    public static final int INSTRUMENTED_END_ASYNC_FOR       = 248;
    public static final int INSTRUMENTED_LOAD_SUPER_ATTR     = 249;
    public static final int INSTRUMENTED_CALL                = 250;
    public static final int INSTRUMENTED_CALL_KW             = 251;
    public static final int INSTRUMENTED_CALL_FUNCTION_EX    = 252;
    public static final int INSTRUMENTED_JUMP_BACKWARD       = 253;
    public static final int INSTRUMENTED_LINE                = 254;
    public static final int ENTER_EXECUTOR                   = 255;
    public static final int ANNOTATIONS_PLACEHOLDER          = 256;
    public static final int JUMP                             = 257;
    public static final int JUMP_IF_FALSE                    = 258;
    public static final int JUMP_IF_TRUE                     = 259;
    public static final int JUMP_NO_INTERRUPT                = 260;
    public static final int LOAD_CLOSURE                     = 261;
    public static final int POP_BLOCK                        = 262;
    public static final int SETUP_CLEANUP                    = 263;
    public static final int SETUP_FINALLY                    = 264;
    public static final int SETUP_WITH                       = 265;
    public static final int STORE_FAST_MAYBE_NULL            = 266;

    private static final boolean[] IN_BASE = {
        true, true, true, false, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true,
        true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true,
        true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true,
        true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true,
        true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true,
        true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true,
        true, false, false, false, false, false, false, false, true, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, true, true, true, true, true, true,
        true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true,
        true, true, true, true, true, true, true
    };

    private static final boolean[] JUMPS = {
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, true, false, true, false, false, false, false, true, true, true, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        true, true, true, true, false, false, true, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, true, false, false,
        false, false, false, false, false, false, false, false, true, false, false, false, false, false, false, false, false, true, true, true,
        true, false, false, false, false, false, false
    };

    private static final boolean[] BACKWARD = {
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, true, true, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false
    };

    /** Opcode number to name, dense, so an unsupported opcode can be named in a message. */
    private static final String[] NAMES = {
        "CACHE", "BINARY_SLICE", "BUILD_TEMPLATE", "BINARY_OP_INPLACE_ADD_UNICODE", "CALL_FUNCTION_EX", "CHECK_EG_MATCH", "CHECK_EXC_MATCH", "CLEANUP_THROW",
        "DELETE_SUBSCR", "END_FOR", "END_SEND", "EXIT_INIT_CHECK", "FORMAT_SIMPLE", "FORMAT_WITH_SPEC", "GET_AITER", "GET_ANEXT",
        "GET_ITER", "RESERVED", "GET_LEN", "GET_YIELD_FROM_ITER", "INTERPRETER_EXIT", "LOAD_BUILD_CLASS", "LOAD_LOCALS", "MAKE_FUNCTION",
        "MATCH_KEYS", "MATCH_MAPPING", "MATCH_SEQUENCE", "NOP", "NOT_TAKEN", "POP_EXCEPT", "POP_ITER", "POP_TOP",
        "PUSH_EXC_INFO", "PUSH_NULL", "RETURN_GENERATOR", "RETURN_VALUE", "SETUP_ANNOTATIONS", "STORE_SLICE", "STORE_SUBSCR", "TO_BOOL",
        "UNARY_INVERT", "UNARY_NEGATIVE", "UNARY_NOT", "WITH_EXCEPT_START", "BINARY_OP", "BUILD_INTERPOLATION", "BUILD_LIST", "BUILD_MAP",
        "BUILD_SET", "BUILD_SLICE", "BUILD_STRING", "BUILD_TUPLE", "CALL", "CALL_INTRINSIC_1", "CALL_INTRINSIC_2", "CALL_KW",
        "COMPARE_OP", "CONTAINS_OP", "CONVERT_VALUE", "COPY", "COPY_FREE_VARS", "DELETE_ATTR", "DELETE_DEREF", "DELETE_FAST",
        "DELETE_GLOBAL", "DELETE_NAME", "DICT_MERGE", "DICT_UPDATE", "END_ASYNC_FOR", "EXTENDED_ARG", "FOR_ITER", "GET_AWAITABLE",
        "IMPORT_FROM", "IMPORT_NAME", "IS_OP", "JUMP_BACKWARD", "JUMP_BACKWARD_NO_INTERRUPT", "JUMP_FORWARD", "LIST_APPEND", "LIST_EXTEND",
        "LOAD_ATTR", "LOAD_COMMON_CONSTANT", "LOAD_CONST", "LOAD_DEREF", "LOAD_FAST", "LOAD_FAST_AND_CLEAR", "LOAD_FAST_BORROW", "LOAD_FAST_BORROW_LOAD_FAST_BORROW",
        "LOAD_FAST_CHECK", "LOAD_FAST_LOAD_FAST", "LOAD_FROM_DICT_OR_DEREF", "LOAD_FROM_DICT_OR_GLOBALS", "LOAD_GLOBAL", "LOAD_NAME", "LOAD_SMALL_INT", "LOAD_SPECIAL",
        "LOAD_SUPER_ATTR", "MAKE_CELL", "MAP_ADD", "MATCH_CLASS", "POP_JUMP_IF_FALSE", "POP_JUMP_IF_NONE", "POP_JUMP_IF_NOT_NONE", "POP_JUMP_IF_TRUE",
        "RAISE_VARARGS", "RERAISE", "SEND", "SET_ADD", "SET_FUNCTION_ATTRIBUTE", "SET_UPDATE", "STORE_ATTR", "STORE_DEREF",
        "STORE_FAST", "STORE_FAST_LOAD_FAST", "STORE_FAST_STORE_FAST", "STORE_GLOBAL", "STORE_NAME", "SWAP", "UNPACK_EX", "UNPACK_SEQUENCE",
        "YIELD_VALUE", null, null, null, null, null, null, null,
        "RESUME", "BINARY_OP_ADD_FLOAT", "BINARY_OP_ADD_INT", "BINARY_OP_ADD_UNICODE", "BINARY_OP_EXTEND", "BINARY_OP_MULTIPLY_FLOAT", "BINARY_OP_MULTIPLY_INT", "BINARY_OP_SUBSCR_DICT",
        "BINARY_OP_SUBSCR_GETITEM", "BINARY_OP_SUBSCR_LIST_INT", "BINARY_OP_SUBSCR_LIST_SLICE", "BINARY_OP_SUBSCR_STR_INT", "BINARY_OP_SUBSCR_TUPLE_INT", "BINARY_OP_SUBTRACT_FLOAT", "BINARY_OP_SUBTRACT_INT", "CALL_ALLOC_AND_ENTER_INIT",
        "CALL_BOUND_METHOD_EXACT_ARGS", "CALL_BOUND_METHOD_GENERAL", "CALL_BUILTIN_CLASS", "CALL_BUILTIN_FAST", "CALL_BUILTIN_FAST_WITH_KEYWORDS", "CALL_BUILTIN_O", "CALL_ISINSTANCE", "CALL_KW_BOUND_METHOD",
        "CALL_KW_NON_PY", "CALL_KW_PY", "CALL_LEN", "CALL_LIST_APPEND", "CALL_METHOD_DESCRIPTOR_FAST", "CALL_METHOD_DESCRIPTOR_FAST_WITH_KEYWORDS", "CALL_METHOD_DESCRIPTOR_NOARGS", "CALL_METHOD_DESCRIPTOR_O",
        "CALL_NON_PY_GENERAL", "CALL_PY_EXACT_ARGS", "CALL_PY_GENERAL", "CALL_STR_1", "CALL_TUPLE_1", "CALL_TYPE_1", "COMPARE_OP_FLOAT", "COMPARE_OP_INT",
        "COMPARE_OP_STR", "CONTAINS_OP_DICT", "CONTAINS_OP_SET", "FOR_ITER_GEN", "FOR_ITER_LIST", "FOR_ITER_RANGE", "FOR_ITER_TUPLE", "JUMP_BACKWARD_JIT",
        "JUMP_BACKWARD_NO_JIT", "LOAD_ATTR_CLASS", "LOAD_ATTR_CLASS_WITH_METACLASS_CHECK", "LOAD_ATTR_GETATTRIBUTE_OVERRIDDEN", "LOAD_ATTR_INSTANCE_VALUE", "LOAD_ATTR_METHOD_LAZY_DICT", "LOAD_ATTR_METHOD_NO_DICT", "LOAD_ATTR_METHOD_WITH_VALUES",
        "LOAD_ATTR_MODULE", "LOAD_ATTR_NONDESCRIPTOR_NO_DICT", "LOAD_ATTR_NONDESCRIPTOR_WITH_VALUES", "LOAD_ATTR_PROPERTY", "LOAD_ATTR_SLOT", "LOAD_ATTR_WITH_HINT", "LOAD_CONST_IMMORTAL", "LOAD_CONST_MORTAL",
        "LOAD_GLOBAL_BUILTIN", "LOAD_GLOBAL_MODULE", "LOAD_SUPER_ATTR_ATTR", "LOAD_SUPER_ATTR_METHOD", "RESUME_CHECK", "SEND_GEN", "STORE_ATTR_INSTANCE_VALUE", "STORE_ATTR_SLOT",
        "STORE_ATTR_WITH_HINT", "STORE_SUBSCR_DICT", "STORE_SUBSCR_LIST_INT", "TO_BOOL_ALWAYS_TRUE", "TO_BOOL_BOOL", "TO_BOOL_INT", "TO_BOOL_LIST", "TO_BOOL_NONE",
        "TO_BOOL_STR", "UNPACK_SEQUENCE_LIST", "UNPACK_SEQUENCE_TUPLE", "UNPACK_SEQUENCE_TWO_TUPLE", null, null, null, null,
        null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null,
        null, null, "INSTRUMENTED_END_FOR", "INSTRUMENTED_POP_ITER", "INSTRUMENTED_END_SEND", "INSTRUMENTED_FOR_ITER", "INSTRUMENTED_INSTRUCTION", "INSTRUMENTED_JUMP_FORWARD",
        "INSTRUMENTED_NOT_TAKEN", "INSTRUMENTED_POP_JUMP_IF_TRUE", "INSTRUMENTED_POP_JUMP_IF_FALSE", "INSTRUMENTED_POP_JUMP_IF_NONE", "INSTRUMENTED_POP_JUMP_IF_NOT_NONE", "INSTRUMENTED_RESUME", "INSTRUMENTED_RETURN_VALUE", "INSTRUMENTED_YIELD_VALUE",
        "INSTRUMENTED_END_ASYNC_FOR", "INSTRUMENTED_LOAD_SUPER_ATTR", "INSTRUMENTED_CALL", "INSTRUMENTED_CALL_KW", "INSTRUMENTED_CALL_FUNCTION_EX", "INSTRUMENTED_JUMP_BACKWARD", "INSTRUMENTED_LINE", "ENTER_EXECUTOR",
        "ANNOTATIONS_PLACEHOLDER", "JUMP", "JUMP_IF_FALSE", "JUMP_IF_TRUE", "JUMP_NO_INTERRUPT", "LOAD_CLOSURE", "POP_BLOCK", "SETUP_CLEANUP",
        "SETUP_FINALLY", "SETUP_WITH", "STORE_FAST_MAYBE_NULL"
    };

    /** {@code MAX_OPCODE + 1} in the interpreter this table came from. */
    public static final int COUNT = 267;

    private Opcodes() {
    }

    /** The name of an opcode number, or {@code <n>} for a number that has none. */
    public static String name(int opcode) {
        return opcode >= 0 && opcode < NAMES.length && NAMES[opcode] != null
                ? NAMES[opcode]
                : "<" + opcode + ">";
    }

    /** True for a number only an adapting interpreter produces — never valid in stored code. */
    public static boolean specialised(int opcode) {
        return opcode < 0 || opcode >= NAMES.length || NAMES[opcode] == null || !IN_BASE[opcode];
    }

    /**
     * Whether an opcode's argument is a relative jump.
     *
     * This is the interpreter's business, not a front-end's: the direction of a jump is part of the
     * instruction set the two stages share. How far apart the jumps sit in the stored code — the
     * cache units between them — is not, and stays with whoever packed the array.
     */
    public static boolean jumpsRelative(int opcode) {
        return opcode >= 0 && opcode < JUMPS.length && JUMPS[opcode];
    }

    /** Whether a relative jump's argument counts backwards from the instruction after the jump. */
    public static boolean jumpsBackward(int opcode) {
        return opcode >= 0 && opcode < BACKWARD.length && BACKWARD[opcode];
    }
}

