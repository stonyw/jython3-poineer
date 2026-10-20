package org.python.core;

/**
 * The interpreter: a dispatch loop over a {@link Frame}, and nothing else.
 *
 * Every case here is a translation of one {@code inst()} in CPython's {@code bytecodes.c}, including
 * the stack shapes, which are the part most often got wrong from memory: a jump argument is relative
 * and its target is resolved by the front-end (D2), {@code FOR_ITER} skips past the {@code END_FOR}
 * that closes its loop, {@code MAKE_FUNCTION} pops only a code object, and the container-append
 * opcodes leave their container on the stack.
 *
 * What this slice does not cover is reported rather than guessed at. Generators, coroutines,
 * {@code with}, user-defined classes and pattern matching are later M3 slices; an opcode belonging to
 * one of them raises {@code NotImplementedError} naming the opcode, so a script that reaches one fails
 * with a sentence instead of a wrong answer.
 */
public final class Interpreter {

    private Interpreter() {
    }

    /**
     * Run {@code frame} to a {@code RETURN_VALUE}.
     *
     * <p>An exception this frame has no handler for leaves by the Java carry-over: it escapes here and
     * is caught by the caller's own {@code run}, whose table is asked about the call that failed.
     *
     * @return the value Python returned from the code object
     */
    public static PyObject run(Frame frame) {
        CodeInstructions code = frame.instructions();
        int limit = code.length();
        try {
            return interpret(frame, code, limit);
        } catch (StackOverflowError overflow) {
            throw PyRaise.raise(
                    ExceptionTypes.RECURSION_ERROR, "maximum recursion depth exceeded");
        }
    }

    /**
     * The dispatch loop: one instruction at a time, until the frame returns.
     *
     * <p>The {@code catch} is CPython's {@code error:} label. It is per instruction rather than around
     * the whole loop because that is what makes a handler work: an exception this frame guards is
     * answered by re-pointing this frame's own instruction pointer and falling through to the next
     * iteration, so the handler runs in the frame that raised with the stack the compiler expected.
     */
    private static PyObject interpret(Frame frame, CodeInstructions code, int limit) {
        while (frame.ip() < limit) {
            int index = frame.ip();
            frame.setIp(index + 1);
            frame.setExecuting(index);
            int opcode = code.opcodeAt(index);
            int arg = code.argAt(index);
            try {
                switch (opcode) {

                    // --- instructions with no observable effect at this stage ---------------
                    //
                    // EXTENDED_ARG carries an argument already folded into the instruction it extends, so
                    // stepping over it is all it needs. RESUME is a no-op at entry to a frame and would
                    // only matter for generators, which are M3.
                    case Opcodes.NOP:
                    case Opcodes.CACHE:
                    case Opcodes.EXTENDED_ARG:
                    case Opcodes.NOT_TAKEN:
                    case Opcodes.RESUME:
                        break;

                    // --- pushing values ------------------------------------------------------
                    case Opcodes.PUSH_NULL:
                        frame.push(null);
                        break;
                    case Opcodes.LOAD_CONST:
                        frame.push(frame.constant(arg));
                        break;
                    case Opcodes.LOAD_SMALL_INT:
                        frame.push(PyInt.of(arg));
                        break;
                    case Opcodes.LOAD_COMMON_CONSTANT:
                        frame.push(commonConstant(arg));
                        break;
                    case Opcodes.LOAD_BUILD_CLASS: {
                        // CPython reads the helper from the builtins of the frame rather than from the
                        // interpreter's own, and raises a NameError of its own wording when it is not
                        // there: `__build_class__` is looked up, not compiled in.
                        PyObject builder =
                                PyRuntime.builtins().get(PyStr.interned("__build_class__"));
                        if (builder == null) {
                            throw PyRaise.raise(
                                    ExceptionTypes.NAME_ERROR, "__build_class__ not found");
                        }
                        frame.push(builder);
                        break;
                    }
                    case Opcodes.LOAD_LOCALS:
                        // A snapshot, which is what `locals()` in a class body is: the dictionary the
                        // body keeps writing into goes on living, and this copy does not follow it.
                        frame.push(nameSpace(frame).copy());
                        break;

                    // --- moving values on the stack ----------------------------------------
                    //
                    // COPY and SWAP both count from the top and both may handle a null left by
                    // PUSH_NULL, which is why they read and write slots rather than popping.
                    case Opcodes.POP_TOP:
                        frame.pop();
                        break;
                    case Opcodes.COPY:
                        frame.push(frame.peek(arg - 1));
                        break;
                    case Opcodes.SWAP: {
                        PyObject top = frame.peek(0);
                        PyObject bottom = frame.peek(arg - 1);
                        frame.replace(arg - 1, top);
                        frame.replace(0, bottom);
                        break;
                    }

                    // --- local variables -----------------------------------------------------
                    case Opcodes.LOAD_FAST:
                    case Opcodes.LOAD_FAST_BORROW:
                    case Opcodes.LOAD_FAST_CHECK: {
                        PyObject value = frame.slot(arg);
                        if (value == null) {
                            throw frame.unbound(arg);
                        }
                        frame.push(value);
                        break;
                    }
                    case Opcodes.LOAD_FAST_LOAD_FAST:
                    case Opcodes.LOAD_FAST_BORROW_LOAD_FAST_BORROW: {
                        pushPair(frame, frame.slot(arg >> 4), frame.slot(arg & 15), arg);
                        break;
                    }
                    case Opcodes.STORE_FAST:
                        frame.setSlot(arg, frame.pop());
                        break;
                    case Opcodes.STORE_FAST_LOAD_FAST: {
                        // The two halves are different slots of the same pair: the value stored is
                        // popped, and the value loaded is read back afterwards, so a null under the
                        // store is not the null that pushPair reports as an unbound local.
                        frame.setSlot(arg >> 4, frame.pop());
                        PyObject loaded = frame.slot(arg & 15);
                        if (loaded == null) {
                            throw frame.unbound(arg & 15);
                        }
                        frame.push(loaded);
                        break;
                    }
                    case Opcodes.STORE_FAST_STORE_FAST: {
                        PyObject top = frame.pop();
                        PyObject below = frame.pop();
                        frame.setSlot(arg >> 4, top);
                        frame.setSlot(arg & 15, below);
                        break;
                    }
                    case Opcodes.LOAD_FAST_AND_CLEAR: {
                        // A comprehension's hidden variable borrows the slot and hands it back at the
                        // end, which is how it stays invisible to the enclosing scope.
                        frame.push(frame.slot(arg));
                        frame.setSlot(arg, null);
                        break;
                    }
                    case Opcodes.DELETE_FAST: {
                        if (frame.slot(arg) == null) {
                            throw frame.unbound(arg);
                        }
                        frame.setSlot(arg, null);
                        break;
                    }

                    // --- names ---------------------------------------------------------------
                    case Opcodes.LOAD_NAME:
                        frame.push(loadName(frame, frame.name(arg)));
                        break;
                    case Opcodes.STORE_NAME:
                        storeName(frame, frame.name(arg), frame.pop());
                        break;
                    case Opcodes.DELETE_NAME:
                        if (nameSpace(frame).get(PyStr.interned(frame.name(arg))) == null) {
                            throw Slots.nameError(frame.name(arg));
                        }
                        nameSpace(frame).getItems()
                                .remove(PyStr.interned(frame.name(arg)));
                        break;
                    case Opcodes.LOAD_GLOBAL: {
                        String name = frame.name(arg >> 1);
                        PyObject value = lookupGlobal(frame, name);
                        frame.push(value);
                        if ((arg & 1) != 0) {
                            frame.push(null);
                        }
                        break;
                    }
                    case Opcodes.STORE_GLOBAL:
                        frame.globals().put(PyStr.interned(frame.name(arg)), frame.pop());
                        break;
                    case Opcodes.DELETE_GLOBAL:
                        if (frame.globals().get(PyStr.interned(frame.name(arg))) == null) {
                            throw Slots.nameError(frame.name(arg));
                        }
                        frame.globals().getItems().remove(PyStr.interned(frame.name(arg)));
                        break;

                    // --- cells and free variables --------------------------------------------
                    case Opcodes.MAKE_CELL: {
                        PyObject current = frame.slot(arg);
                        if (!(current instanceof PyCell)) {
                            // The slot may already hold a value: a parameter captured by an inner
                            // function arrives bound, and its cell starts life containing it.
                            frame.setSlot(arg, new PyCell(current));
                        }
                        break;
                    }
                    case Opcodes.COPY_FREE_VARS: {
                        PyCell[] cells = frame.function.closureCells();
                        int offset = frame.code().localCount() - arg;
                        for (int i = 0; i < arg; i++) {
                            frame.setSlot(offset + i, cells[i]);
                        }
                        break;
                    }
                    case Opcodes.LOAD_DEREF: {
                        PyObject value = frame.cell(arg).value();
                        if (value == null) {
                            throw frame.unbound(arg);
                        }
                        frame.push(value);
                        break;
                    }
                    case Opcodes.STORE_DEREF:
                        frame.cell(arg).set(frame.pop());
                        break;
                    case Opcodes.DELETE_DEREF: {
                        if (frame.cell(arg).value() == null) {
                            throw frame.unbound(arg);
                        }
                        frame.cell(arg).set(null);
                        break;
                    }

                    // --- building containers --------------------------------------------------
                    case Opcodes.BUILD_TUPLE:
                        frame.push(PyTuple.of(frame.take(arg)));
                        break;
                    case Opcodes.BUILD_LIST:
                        frame.push(PyList.of(frame.take(arg)));
                        break;
                    case Opcodes.BUILD_SET: {
                        PySet set = PySet.empty();
                        for (PyObject item : frame.take(arg)) {
                            set.add(item);
                        }
                        frame.push(set);
                        break;
                    }
                    case Opcodes.BUILD_MAP: {
                        PyObject[] entries = frame.take(2 * arg);
                        PyDict map = PyDict.empty();
                        for (int i = 0; i + 1 < entries.length; i += 2) {
                            map.put(entries[i], entries[i + 1]);
                        }
                        frame.push(map);
                        break;
                    }
                    case Opcodes.BUILD_STRING: {
                        StringBuilder out = new StringBuilder();
                        PyObject[] pieces = frame.take(arg);
                        for (int i = 0; i < pieces.length; i++) {
                            if (!(pieces[i] instanceof PyStr text)) {
                                throw Slots.typeError(
                                        "sequence item %d: expected str instance, %s found",
                                        i, pieces[i].getType().name());
                            }
                            out.append(text.getValue());
                        }
                        frame.push(PyStr.of(out.toString()));
                        break;
                    }
                    case Opcodes.BUILD_SLICE: {
                        PyObject[] bounds = frame.take(arg);
                        frame.push(PySlice.of(
                                bounds[0],
                                bounds[1],
                                arg == 3 ? bounds[2] : PyNone.INSTANCE));
                        break;
                    }

                    // --- comprehension and unpacking helpers ---------------------------------
                    case Opcodes.LIST_APPEND:
                        asList(frame.peek(arg)).add(frame.pop());
                        break;
                    case Opcodes.SET_ADD:
                        asSet(frame.peek(arg)).add(frame.pop());
                        break;
                    case Opcodes.MAP_ADD: {
                        PyDict map = asDict(frame.peek(arg + 1));
                        PyObject value = frame.pop();
                        PyObject key = frame.pop();
                        map.put(key, value);
                        break;
                    }
                    case Opcodes.LIST_EXTEND:
                        extend(asList(frame.peek(arg)), frame.pop());
                        break;
                    case Opcodes.SET_UPDATE:
                        update(asSet(frame.peek(arg)), frame.pop());
                        break;
                    case Opcodes.DICT_UPDATE:
                        mergeInto(asDict(frame.peek(arg)), frame.pop(), false);
                        break;
                    case Opcodes.DICT_MERGE:
                        mergeInto(asDict(frame.peek(arg)), frame.pop(), true);
                        break;

                    case Opcodes.GET_ITER:
                        frame.push(Slots.iterator(frame.pop()));
                        break;
                    case Opcodes.FOR_ITER: {
                        // An exhausted iterator jumps past the END_FOR that closes the loop: CPython's
                        // _FOR_ITER jumps oparg+1 units so that POP_ITER, not END_FOR, pops the iterator.
                        PyObject target = frame.peek(0);
                        if (!(target instanceof PyIterator iterator)) {
                            throw Slots.typeError(
                                    "'%s' object is not an iterator", target.getType().name());
                        }
                        PyObject next = iterator.nextOrNull();
                        if (next == null) {
                            frame.setIp(frame.jumpTarget(index) + 1);
                        } else {
                            frame.push(next);
                        }
                        break;
                    }
                    case Opcodes.END_FOR:
                        // Only reached when a generator's return value is delivered here; the loop
                        // variable it discards is the value on top.
                        frame.pop();
                        break;
                    case Opcodes.POP_ITER:
                        frame.pop();
                        break;

                    case Opcodes.UNPACK_SEQUENCE:
                        unpack(frame, arg, 0, false);
                        break;
                    case Opcodes.UNPACK_EX:
                        unpack(frame, arg & 0xff, arg >> 8, true);
                        break;

                    // --- operators ------------------------------------------------------------
                    case Opcodes.BINARY_OP: {
                        PyObject right = frame.pop();
                        PyObject left = frame.pop();
                        if (arg == BinaryOp.SUBSCR_ARG) {
                            frame.push(Slots.getItem(left, right));
                            break;
                        }
                        BinaryOp op = BinaryOp.of(arg);
                        if (op == null) {
                            throw Slots.notImplemented("BINARY_OP argument " + arg);
                        }
                        frame.push(Slots.augmented(op, left, right, BinaryOp.augmented(arg)));
                        break;
                    }
                    case Opcodes.COMPARE_OP: {
                        CompareOp cmp = CompareOp.of(arg);
                        if (cmp == null) {
                            throw Slots.notImplemented("COMPARE_OP argument " + arg);
                        }
                        PyObject right = frame.pop();
                        PyObject left = frame.pop();
                        PyObject result = Slots.compare(cmp, left, right);
                        frame.push(CompareOp.wrapsInBool(arg) ? PyBool.of(Slots.truth(result)) : result);
                        break;
                    }
                    case Opcodes.IS_OP: {
                        PyObject right = frame.pop();
                        PyObject left = frame.pop();
                        frame.push(PyBool.of((left == right) != (arg == 1)));
                        break;
                    }
                    case Opcodes.CONTAINS_OP: {
                        PyObject container = frame.pop();
                        PyObject item = frame.pop();
                        frame.push(PyBool.of(Slots.contains(container, item) != (arg == 1)));
                        break;
                    }
                    case Opcodes.TO_BOOL:
                        frame.push(PyBool.of(Slots.truth(frame.pop())));
                        break;
                    case Opcodes.UNARY_NEGATIVE:
                        frame.push(Slots.unary(UnaryOp.NEGATIVE, frame.pop()));
                        break;
                    case Opcodes.UNARY_INVERT:
                        frame.push(Slots.unary(UnaryOp.INVERT, frame.pop()));
                        break;
                    case Opcodes.UNARY_NOT: {
                        PyObject value = frame.pop();
                        // The compiler guarantees a bool here (TO_BOOL or a test opcode precedes it),
                        // so the general truth test is only the defensive path.
                        frame.push(PyBool.of(!(value instanceof PyBool bool
                                ? bool.getValue()
                                : Slots.truth(value))));
                        break;
                    }
                    case Opcodes.CALL_INTRINSIC_1:
                        frame.push(intrinsic(frame, arg));
                        break;

                    // --- jumps ---------------------------------------------------------------
                    case Opcodes.JUMP_FORWARD:
                    case Opcodes.JUMP_BACKWARD:
                    case Opcodes.JUMP_BACKWARD_NO_INTERRUPT:
                        frame.setIp(frame.jumpTarget(index));
                        break;
                    case Opcodes.POP_JUMP_IF_TRUE:
                        jumpIf(frame, index, Slots.truth(frame.pop()));
                        break;
                    case Opcodes.POP_JUMP_IF_FALSE:
                        jumpIf(frame, index, !Slots.truth(frame.pop()));
                        break;
                    case Opcodes.POP_JUMP_IF_NONE:
                        jumpIf(frame, index, frame.pop() == PyNone.INSTANCE);
                        break;
                    case Opcodes.POP_JUMP_IF_NOT_NONE:
                        jumpIf(frame, index, frame.pop() != PyNone.INSTANCE);
                        break;

                    // --- calls ---------------------------------------------------------------
                    case Opcodes.CALL: {
                        PyObject[] args = frame.take(arg);
                        PyObject self = frame.pop();
                        PyObject callable = frame.pop();
                        frame.push(Slots.call(
                                callable, self == null ? args : prepend(self, args), null));
                        break;
                    }
                    case Opcodes.CALL_KW: {
                        PyTuple keywords = (PyTuple) frame.pop();
                        PyObject[] args = frame.take(arg);
                        PyObject self = frame.pop();
                        PyObject callable = frame.pop();
                        frame.push(Slots.call(
                                callable,
                                self == null ? args : prepend(self, args),
                                keywords));
                        break;
                    }
                    case Opcodes.CALL_FUNCTION_EX: {
                        PyObject keywords = frame.pop();
                        PyObject positional = frame.pop();
                        frame.pop();
                        PyObject callable = frame.pop();
                        frame.push(callWithUnpacking(callable, positional, keywords));
                        break;
                    }

                    // --- functions -----------------------------------------------------------
                    case Opcodes.MAKE_FUNCTION:
                        frame.push(makeFunction(frame, frame.pop()));
                        break;
                    case Opcodes.SET_FUNCTION_ATTRIBUTE: {
                        // The function is on top: MAKE_FUNCTION leaves it above the defaults, closure
                        // or annotation value it is about to be given, which is what it did not pop.
                        PyObject function = frame.pop();
                        PyObject attribute = frame.pop();
                        if (!(function instanceof PyFunction python)) {
                            throw Slots.typeError(
                                    "cannot set attribute on %s", function.getType().name());
                        }
                        python.setAttribute(arg, attribute);
                        frame.push(function);
                        break;
                    }

                    // --- attributes ----------------------------------------------------------
                    case Opcodes.LOAD_ATTR: {
                        String attributeName = frame.name(arg >> 1);
                        PyObject owner = frame.pop();
                        PyObject attribute = Slots.getAttr(owner, attributeName);
                        frame.push(attribute);
                        if ((arg & 1) != 0) {
                            // The method form hands CALL a receiver slot as well, and CPython fills it
                            // only to avoid making a bound method it would immediately take apart. The
                            // lookup above has already bound whatever binds itself — a class's function
                            // reached through one of its values, a Java method to its object, a
                            // classmethod to its class — so what is left is to hand over nothing, and
                            // let an attribute that came back unbound be called as it came: which is
                            // why `self.weight(2)` to a `@staticmethod` and `span.fget(m)` to a
                            // property's getter are both one-argument calls.
                            frame.push(null);
                        }
                        break;
                    }
                    case Opcodes.STORE_ATTR: {
                        // Owner on top, value below it — which is the opposite of the order the
                        // compiler loads them, and the reason the name index is read before either.
                        PyObject owner = frame.pop();
                        PyObject value = frame.pop();
                        Slots.setAttr(owner, frame.name(arg), value);
                        break;
                    }
                    case Opcodes.DELETE_ATTR:
                        Slots.delAttr(frame.pop(), frame.name(arg));
                        break;

                    // --- subscripting --------------------------------------------------------
                    case Opcodes.BINARY_SLICE: {
                        // a[i:j] builds the slice and indexes with it in one step, so the two bounds
                        // are separate stack entries and there is no step to read.
                        PyObject stop = frame.pop();
                        PyObject start = frame.pop();
                        PyObject container = frame.pop();
                        frame.push(Slots.getItem(
                                container, PySlice.of(start, stop, PyNone.INSTANCE)));
                        break;
                    }
                    case Opcodes.STORE_SLICE: {
                        PyObject stop = frame.pop();
                        PyObject start = frame.pop();
                        PyObject container = frame.pop();
                        PyObject value = frame.pop();
                        Slots.setItem(
                                container,
                                PySlice.of(start, stop, PyNone.INSTANCE),
                                value);
                        break;
                    }
                    case Opcodes.STORE_SUBSCR: {
                        PyObject key = frame.pop();
                        PyObject container = frame.pop();
                        Slots.setItem(container, key, frame.pop());
                        break;
                    }
                    case Opcodes.DELETE_SUBSCR: {
                        PyObject key = frame.pop();
                        Slots.delItem(frame.pop(), key);
                        break;
                    }

                    // --- returning and raising ----------------------------------------------
                    case Opcodes.RETURN_VALUE:
                        return frame.pop();
                    case Opcodes.RAISE_VARARGS:
                        raise(frame, arg);
                        break;

                    // --- handling an exception ---------------------------------------------
                    //
                    // A handler is entered with its exception on top of the stack, put there while
                    // unwinding. These four opcodes are the only way it becomes Python-visible state:
                    // there is no block stack and no runtime stack of handlers to consult.
                    case Opcodes.PUSH_EXC_INFO: {
                        // The exception the enclosing handler was running for, if any, goes under this
                        // one so that POP_EXCEPT can put it back; a bare None is "no handler outside me".
                        PyObject inHandler = frame.pop();
                        PyObject enclosing = PyRuntime.handledException();
                        PyRuntime.setHandledException(inHandler);
                        frame.push(enclosing);
                        frame.push(inHandler);
                        break;
                    }
                    case Opcodes.CHECK_EXC_MATCH: {
                        // Neither value is taken off the stack first: CPython leaves both where they are
                        // when it refuses the clause itself, and the level an unwind pops to discards any
                        // leftovers regardless.
                        PyObject clause = frame.peek(0);
                        frame.replace(0, PyBool.of(catches((PyBaseException) frame.peek(1), clause)));
                        break;
                    }
                    case Opcodes.POP_EXCEPT:
                        PyRuntime.setHandledException(frame.pop());
                        break;
                    case Opcodes.RERAISE: {
                        PyObject again = frame.pop();
                        if (arg != 0) {
                            // The offset the handler came in with, kept under the exception for this
                            // moment: the report names where the exception first failed, not this
                            // instruction. The table is still searched from the re-raise itself, which is
                            // why moving the report site must not move the lookup site.
                            frame.restoreRaisedAt(lastiUnit(frame, arg));
                        }
                        throw PyRaise.of(asException(again));
                    }

                    // --- importing -----------------------------------------------------------
                    case Opcodes.IMPORT_NAME:
                        frame.push(importModule(frame, arg));
                        break;
                    case Opcodes.IMPORT_FROM:
                        frame.push(importFrom(frame, frame.name(arg)));
                        break;

                    // --- f-strings -----------------------------------------------------------
                    case Opcodes.CONVERT_VALUE:
                        frame.push(convertValue(frame.pop(), arg));
                        break;
                    case Opcodes.FORMAT_SIMPLE: {
                        PyObject value = frame.pop();
                        // An exact str needs no work, which is why CPython keeps that case inline.
                        frame.push(value instanceof PyStr text
                                ? text
                                : PyStr.of(Formats.apply(value, "")));
                        break;
                    }
                    case Opcodes.FORMAT_WITH_SPEC: {
                        PyObject spec = frame.pop();
                        PyObject value = frame.pop();
                        frame.push(PyStr.of(
                                Formats.apply(value, textOrEmpty(spec))));
                        break;
                    }

                    case Opcodes.SETUP_ANNOTATIONS: {
                        PyDict namespace = nameSpace(frame);
                        PyStr key = PyStr.interned("__annotations__");
                        if (namespace.get(key) == null) {
                            namespace.put(key, PyDict.empty());
                        }
                        break;
                    }

                    case Opcodes.INTERPRETER_EXIT:
                        throw Slots.runtimeError("INTERPRETER_EXIT outside a frame");

                    default:
                        throw notCovered(opcode, arg);
                }
            } catch (PyRaise raised) {
                unwind(frame, raised);
            }
        }
        throw Slots.runtimeError(
                "ran off the end of " + frame.code().qualName() + " without returning");
    }

    /**
     * CPython's {@code exception_unwind}, for this frame and this exception only.
     *
     * <p>The row covering the instruction that failed says how much of the value stack the handler
     * expects, whether to leave the offset of the failed instruction below the exception for a
     * {@code RERAISE} to restore, and where to resume. Resuming is done by pointing the instruction
     * pointer there and returning, so the loop above runs a handler as what it always was: more
     * instructions of the same frame, on a stack the compiler already knew the depth of.
     *
     * <p>No exception is stored on the way in: Java is carrying it, and the runtime's handled slot is
     * written by the handler's own {@code PUSH_EXC_INFO}, which is what lets {@code sys.exc_info()}, a
     * bare {@code raise} and an implicit {@code __context__} read the exception a handler is running for
     * rather than whatever is merely in flight.
     *
     * @throws PyRaise unchanged, when this frame has no row covering that instruction
     */
    private static void unwind(Frame frame, PyRaise raised) {
        PyCode.Handler handler = frame.code().handlerFor(frame.executing());
        if (handler == null) {
            throw raised;
        }
        frame.popTo(handler.stackLevel());
        if (handler.pushLasti()) {
            frame.push(PyInt.of(frame.unitRaisedAt()));
        }
        frame.push(raised.value());
        frame.setIp(handler.targetIndex());
    }

    // --- helpers -------------------------------------------------------------------------

    private static void jumpIf(Frame frame, int index, boolean take) {
        if (take) {
            frame.setIp(frame.jumpTarget(index));
        }
    }

    private static void pushPair(Frame frame, PyObject first, PyObject second, int arg) {
        if (first == null) {
            throw frame.unbound(arg >> 4);
        }
        if (second == null) {
            throw frame.unbound(arg & 15);
        }
        frame.push(first);
        frame.push(second);
    }

    /** The three exceptions {@code LOAD_COMMON_CONSTANT} can name, by {@code opcode}'s numbering. */
    private static PyObject commonConstant(int arg) {
        return switch (arg) {
            case 0 -> ExceptionTypes.ASSERTION_ERROR;
            case 1 -> ExceptionTypes.NOT_IMPLEMENTED_ERROR;
            case 2 -> ExceptionTypes.SYSTEM_ERROR;
            default -> throw Slots.notImplemented("common constant " + arg);
        };
    }

    private static PyRaise notCovered(int opcode, int arg) {
        String name = Opcodes.name(opcode);
        if (Opcodes.specialised(opcode)) {
            return Slots.notImplemented(
                    "the specialized opcode " + name + "; the code object was not read unspecialized");
        }
        return Slots.notImplemented("the opcode " + name + " (argument " + arg + ")");
    }

    // --- names ---------------------------------------------------------------------------

    private static PyDict nameSpace(Frame frame) {
        PyDict locals = frame.locals();
        return locals == null ? frame.globals() : locals;
    }

    private static PyObject loadName(Frame frame, String name) {
        PyStr key = PyStr.interned(name);
        PyDict locals = frame.locals();
        if (locals != null) {
            PyObject value = locals.get(key);
            if (value != null) {
                return value;
            }
        }
        return lookupGlobal(frame, name);
    }

    /** Globals, then builtins: CPython's order for a name that is not local. */
    private static PyObject lookupGlobal(Frame frame, String name) {
        PyStr key = PyStr.interned(name);
        PyObject value = frame.globals().get(key);
        if (value != null) {
            return value;
        }
        value = PyRuntime.builtins().get(key);
        if (value != null) {
            return value;
        }
        throw Slots.nameError(name);
    }

    private static void storeName(Frame frame, String name, PyObject value) {
        nameSpace(frame).put(PyStr.interned(name), value);
    }

    // --- containers ----------------------------------------------------------------------

    private static PyList asList(PyObject value) {
        if (value instanceof PyList list) {
            return list;
        }
        throw Slots.typeError("'%s' object is not a list", value.getType().name());
    }

    private static PySet asSet(PyObject value) {
        if (value instanceof PySet set) {
            return set;
        }
        throw Slots.typeError("'%s' object is not a set", value.getType().name());
    }

    private static PyDict asDict(PyObject value) {
        if (value instanceof PyDict map) {
            return map;
        }
        throw Slots.typeError("'%s' object is not a dict", value.getType().name());
    }

    private static void extend(PyList list, PyObject iterable) {
        if (!Slots.isIterable(iterable)) {
            throw Slots.typeError(
                    "Value after * must be an iterable, not %s", iterable.getType().name());
        }
        PyIterator iterator = Slots.iterator(iterable);
        PyObject item;
        while ((item = iterator.nextOrNull()) != null) {
            list.add(item);
        }
    }

    private static void update(PySet set, PyObject iterable) {
        PyIterator iterator = Slots.iterator(iterable);
        PyObject item;
        while ((item = iterator.nextOrNull()) != null) {
            set.add(item);
        }
    }

    /**
     * {@code DICT_UPDATE} and {@code DICT_MERGE}, which differ only in how they complain: a merge
     * into a call's keyword dictionary wants the keys to be strings, an update accepts any mapping.
     */
    private static void mergeInto(PyDict target, PyObject source, boolean keywords) {
        if (source instanceof PyDict map) {
            for (java.util.Map.Entry<PyObject, PyObject> entry : map.getItems().entrySet()) {
                if (keywords && !(entry.getKey() instanceof PyStr)) {
                    throw Slots.typeError("keywords must be strings, not %s",
                            entry.getKey().getType().name());
                }
                target.put(entry.getKey(), entry.getValue());
            }
            return;
        }
        if (!Slots.isMapping(source)) {
            throw Slots.typeError("'%s' object is not a mapping", source.getType().name());
        }
        PyIterator keys = Slots.iterator(source);
        PyObject key;
        while ((key = keys.nextOrNull()) != null) {
            target.put(key, Slots.getItem(source, key));
        }
    }

    // --- unpacking ----------------------------------------------------------------------

    /**
     * {@code UNPACK_SEQUENCE} and {@code UNPACK_EX}, which share the reading and differ in what they
     * leave behind: {@code before} values, a list of the rest, then {@code after} values, with the
     * first element of the sequence on top so that the stores that follow happen in source order.
     *
     * Only {@code UNPACK_EX} passes a nonzero {@code starred}. It matters for the wording: a starred
     * target can absorb the surplus, so it never reports "too many" and its shortage says
     * "at least" — which is what the absence of a fixed expectation looks like from Python.
     */
    private static void unpack(Frame frame, int before, int after, boolean starred) {
        PyObject source = frame.pop();
        java.util.List<PyObject> items = new java.util.ArrayList<>();
        if (source instanceof PyTuple tuple) {
            for (int i = 0; i < tuple.size(); i++) {
                items.add(tuple.at(i));
            }
        } else if (source instanceof PyList list) {
            items.addAll(list.getItems());
        } else if (!Slots.isIterable(source)) {
            throw Slots.typeError(
                    "cannot unpack non-iterable %s object", source.getType().name());
        } else {
            PyIterator iterator = Slots.iterator(source);
            PyObject item;
            while ((item = iterator.nextOrNull()) != null) {
                items.add(item);
            }
        }
        int wanted = before + after;
        if (items.size() < wanted) {
            throw Slots.valueError(
                    "not enough values to unpack (expected %s%d, got %d)",
                    starred ? "at least " : "", wanted, items.size());
        }
        if (!starred && items.size() > wanted) {
            throw Slots.valueError(
                    "too many values to unpack (expected %d, got %d)", wanted, items.size());
        }
        for (int i = items.size() - 1; i >= items.size() - after; i--) {
            frame.push(items.get(i));
        }
        if (starred) {
            frame.push(PyList.of(
                    items.subList(before, items.size() - after).toArray(new PyObject[0])));
        }
        for (int i = before - 1; i >= 0; i--) {
            // Reverse order, so the leftmost element ends up on top and the stores that follow it
            // happen in source order — the convention UNPACK_SEQUENCE is defined by.
            frame.push(items.get(i));
        }
    }

    // --- calls --------------------------------------------------------------------------

    private static PyObject[] prepend(PyObject first, PyObject[] rest) {
        PyObject[] all = new PyObject[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    /** {@code CALL_FUNCTION_EX}: {@code f(*args, **kwargs)} with both halves already built. */
    private static PyObject callWithUnpacking(PyObject callable, PyObject positional, PyObject keywords) {
        java.util.List<PyObject> args = new java.util.ArrayList<>();
        if (positional == null) {
            throw Slots.typeError("argument after * must be an iterable, not NoneType");
        }
        if (!Slots.isIterable(positional)) {
            throw Slots.typeError(
                    "argument after * must be an iterable, not %s", positional.getType().name());
        }
        PyIterator iterator = Slots.iterator(positional);
        PyObject item;
        while ((item = iterator.nextOrNull()) != null) {
            args.add(item);
        }
        if (keywords == null) {
            return Slots.call(callable, args.toArray(new PyObject[0]), null);
        }
        if (!(keywords instanceof PyDict map)) {
            throw Slots.typeError(
                    "argument after ** must be a mapping, not %s", keywords.getType().name());
        }
        PyObject[] names = new PyObject[map.size()];
        int at = 0;
        for (java.util.Map.Entry<PyObject, PyObject> entry : map.getItems().entrySet()) {
            if (!(entry.getKey() instanceof PyStr key)) {
                throw Slots.typeError(
                        "keywords must be strings, not %s", entry.getKey().getType().name());
            }
            names[at++] = key;
            args.add(entry.getValue());
        }
        return Slots.call(callable, args.toArray(new PyObject[0]), PyTuple.of(names));
    }

    private static PyObject makeFunction(Frame frame, PyObject value) {
        if (!(value instanceof PyCode code)) {
            throw Slots.typeError(
                    "argument to MAKE_FUNCTION must be a code object, not %s",
                    value.getType().name());
        }
        return new PyFunction(code, frame.globals());
    }

    // --- misc ---------------------------------------------------------------------------

    private static PyObject convertValue(PyObject value, int conversion) {
        return switch (conversion) {
            case 1 -> PyStr.of(Slots.str(value));
            case 2 -> PyStr.of(Slots.repr(value));
            case 3 -> PyStr.of(ascii(value));
            default -> throw Slots.notImplemented("conversion " + conversion);
        };
    }

    /** {@code !a}: repr, with anything above {@code 0x7f} left as an escape. */
    private static String ascii(PyObject value) {
        StringBuilder out = new StringBuilder();
        for (char c : Slots.repr(value).toCharArray()) {
            out.append(c < 0x80 ? c : String.format("\\u%04x", (int) c));
        }
        return out.toString();
    }

    private static String textOrEmpty(PyObject value) {
        return value == null ? "" : Slots.str(value);
    }

    private static PyObject intrinsic(Frame frame, int id) {
        switch (id) {
            case 5:
                return Slots.unary(UnaryOp.POSITIVE, frame.pop());
            case 6: {
                PyObject value = frame.pop();
                if (value instanceof PyList list) {
                    PyObject[] items = list.getItems().toArray(new PyObject[0]);
                    return PyTuple.of(items);
                }
                return PyTuple.of(drain(Slots.iterator(value)).toArray(new PyObject[0]));
            }
            case 2:
                // `from m import *`: the module's public names go into the namespace we are in.
                importAll(frame, frame.pop());
                return PyNone.INSTANCE;
            case 3: {
                PyObject value = frame.pop();
                throw PyRaise.raise(ExceptionTypes.STOP_ITERATION, value);
            }
            default:
                throw Slots.notImplemented("intrinsic " + id);
        }
    }

    private static java.util.List<PyObject> drain(PyIterator iterator) {
        java.util.List<PyObject> items = new java.util.ArrayList<>();
        PyObject item;
        while ((item = iterator.nextOrNull()) != null) {
            items.add(item);
        }
        return items;
    }

    /**
     * {@code from m import *}.
     *
     * <p>{@code __all__} is the module's own answer to what the star stands for, and this runtime does not
     * get to guess it — a Java package can only say which of its classes a name like {@code ArrayList}
     * came from by listing them, and a Python module with a private helper has to be able to keep it out.
     * Without a list, the fallback is Python's: every name in the module's dictionary that does not begin
     * with an underscore.
     */
    private static void importAll(Frame frame, PyObject module) {
        PyObject published = module == null ? null : module.getAttr("__all__");
        if (published != null && published != PyNotImplemented.INSTANCE) {
            for (PyObject entry : drain(Slots.iterator(published))) {
                if (!(entry instanceof PyStr name)) {
                    throw Slots.typeError(
                            "Item in %s.__all__ must be str, not %s",
                            textOrEmpty(module.getAttr("__name__")), entry.getType().name());
                }
                // A name on the list that the module does not have is an AttributeError, which is what
                // makes the list a promise rather than a hint.
                storeName(frame, name.getValue(), Slots.getAttr(module, name.getValue()));
            }
            return;
        }
        if (module instanceof PyModule python) {
            for (java.util.Map.Entry<PyObject, PyObject> entry : python.dictionary().getItems().entrySet()) {
                if (!((PyStr) entry.getKey()).getValue().startsWith("_")) {
                    storeName(frame, ((PyStr) entry.getKey()).getValue(), entry.getValue());
                }
            }
            return;
        }
        throw Slots.typeError(
                "cannot import from %s", module == null ? "None" : module.getType().name());
    }

    private static PyObject importModule(Frame frame, int nameIndex) {
        PyObject fromlist = frame.pop();
        frame.pop(); // the level: only absolute imports exist until the M4 finder reads sys.path
        String dotted = frame.name(nameIndex);
        // Loading the full name is what makes `import a.b` work at all, because the loader installs
        // `b` into `a` on the way. What the statement then binds is the head, and only a from-import
        // — or `import a.b as c`, which is a from-import in disguise — asks for the leaf.
        PyObject leaf = PyRuntime.importModule(dotted);
        int at = dotted.indexOf('.');
        if (at >= 0 && (fromlist instanceof PyNone || isBlank(fromlist))) {
            return PyRuntime.importModule(dotted.substring(0, at));
        }
        return leaf;
    }

    private static boolean isBlank(PyObject fromlist) {
        // CPython's own spelling for "no names asked for" when __import__ is called by hand.
        return fromlist instanceof PyStr text && text.getValue().isEmpty();
    }

    private static PyObject importFrom(Frame frame, String attribute) {
        PyObject module = frame.peek(0);
        try {
            return Slots.getAttr(module, attribute);
        } catch (PyRaise missing) {
            // CPython converts this one: an attribute that is not there during an import is an
            // ImportError that names both the attribute and the module it was wanted from.
            if (!missing.value().exceptionType().matches(ExceptionTypes.ATTRIBUTE_ERROR)) {
                throw missing;
            }
            throw Slots.importError(
                    "cannot import name '%s' from '%s'", attribute, moduleName(module));
        }
    }

    /** {@code module.__name__} when it has one, since that is what CPython puts in the message. */
    private static String moduleName(PyObject module) {
        PyObject name = module.getAttr("__name__");
        return name instanceof PyStr text
                ? text.getValue()
                : module.getType().name();
    }

    /**
     * {@code RAISE_VARARGS}: turn what is on the stack into an exception and let the loop's
     * {@code catch} take it to the table.
     *
     * <p>CPython keeps two pieces of exception state, and only the second is Python-visible: the
     * exception in flight ({@code tstate->current_exception}) and the exception a handler is running
     * for ({@code exc_info->exc_value}). A raise reads the second for its implicit {@code __context__}
     * and leaves it alone — {@code PUSH_EXC_INFO} in the handler that catches this one is what changes
     * it — and a bare {@code raise} re-raises exactly that second one, which is why a bare raise works
     * in a function called from a handler.
     */
    private static void raise(Frame frame, int count) {
        if (count == 0) {
            if (!(PyRuntime.handledException() instanceof PyBaseException active)) {
                throw Slots.runtimeError("No active exception to re-raise");
            }
            throw PyRaise.of(active);
        }
        PyObject cause = count == 2 ? frame.pop() : null;
        PyBaseException raised = asException(frame.pop());
        if (cause != null) {
            // `from None` names no cause but still says the context is not to be reported.
            raised.setCause(
                    cause instanceof PyNone ? null : asException(
                            cause, "exception causes must derive from BaseException"));
        }
        if (PyRuntime.handledException() instanceof PyBaseException previous
                && previous != raised) {
            cutContextCycle(previous, raised);
            raised.setContext(previous);
        }
        throw PyRaise.of(raised);
    }

    /**
     * CPython's guard against a {@code __context__} cycle: walk the handled exception's own chain and
     * cut it where it would reach the one about to be raised.
     *
     * <p>Without the cut, {@code except E: e.__context__ = e; raise F} leaves a chain a report cannot
     * finish walking. The slow pointer is CPython's own answer to a cycle that already exists, which the
     * walk would otherwise spin on forever.
     */
    private static void cutContextCycle(PyBaseException handled, PyBaseException raised) {
        PyBaseException slow = handled;
        boolean moveSlow = false;
        for (PyBaseException at = handled; ; ) {
            PyBaseException next = at.context() instanceof PyBaseException following ? following : null;
            if (next == null) {
                return;
            }
            if (next == raised) {
                at.setContext(null);
                return;
            }
            at = next;
            if (moveSlow) {
                if (!(slow.context() instanceof PyBaseException following)) {
                    return;
                }
                slow = following;
                if (slow == at) {
                    return;
                }
            }
            moveSlow = !moveSlow;
        }
    }

    /**
     * Whether {@code clause} catches {@code error}, which is CPython's two steps in its own order:
     * validate the clause, then ask the exception.
     *
     * <p>The validation is one level deep on purpose. {@code except ((TypeError, KeyError), ValueError):}
     * is refused rather than flattened, because a tuple inside the clause is not an exception class, and
     * {@code CHECK_EXC_MATCH} answers with a {@code TypeError} about the clause before the exception is
     * ever compared to it.
     */
    private static boolean catches(PyBaseException error, PyObject clause) {
        if (clause instanceof PyTuple items) {
            for (int i = 0; i < items.size(); i++) {
                requireExceptionClass(items.at(i));
            }
            for (int i = 0; i < items.size(); i++) {
                if (error.matches((PyType) items.at(i))) {
                    return true;
                }
            }
            return false;
        }
        requireExceptionClass(clause);
        return error.matches((PyType) clause);
    }

    /** {@code PyExceptionClass_Check}, whose refusal is the sentence Python users recognise. */
    private static void requireExceptionClass(PyObject clause) {
        if (!(clause instanceof PyType type) || !type.matches(ExceptionTypes.BASE_EXCEPTION)) {
            throw Slots.typeError(
                    "catching classes that do not inherit from BaseException is not allowed");
        }
    }

    /** The offset a {@code RERAISE} restores, read off the value its handler left under the exception. */
    private static int lastiUnit(Frame frame, int kept) {
        PyObject value = frame.peek(kept - 1);
        if (!(value instanceof PyInt offset) || !offset.fitsLong()) {
            throw Slots.runtimeError(
                    "a handler left something that is not an offset for a re-raise to restore");
        }
        return (int) offset.getValue().longValue();
    }

    private static PyBaseException asException(PyObject value) {
        return asException(value, "exceptions must derive from BaseException");
    }

    /**
     * What {@code raise} accepts: an instance, or a class to instantiate with no arguments.
     *
     * <p>The refusal differs between the two places CPython checks it, so the message is this
     * function's to name — a bad raise says "exceptions", a bad {@code from} says "exception causes".
     */
    private static PyBaseException asException(PyObject value, String refusal) {
        if (value instanceof PyBaseException exception) {
            return exception;
        }
        if (value instanceof PyType type && type.matches(ExceptionTypes.BASE_EXCEPTION)) {
            // `raise ValueError` names the class: CPython instantiates it with no arguments.
            PyObject instance = Slots.call(type, new PyObject[0], null);
            if (instance instanceof PyBaseException exception) {
                return exception;
            }
        }
        throw Slots.typeError(refusal);
    }
}
