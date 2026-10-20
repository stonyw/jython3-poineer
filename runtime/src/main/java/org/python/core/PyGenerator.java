package org.python.core;

/**
 * A generator: one {@link Frame} that is not on the interpreter's stack, and the state that says so.
 *
 * <p>Everything the type answers follows from owning a frame instead of running one. The frame is made by
 * an ordinary call and left where that call put it, so the first step lands on the {@code POP_TOP} after
 * {@code RETURN_GENERATOR} and every later one on the {@code RESUME} after the yield that stopped it.
 * Nothing links it to whoever resumed it — which is why {@code gi_frame.f_back} reads {@code None} between
 * steps and the caller's frame during one — and a step runs the body to the next yield and comes back, so
 * the interpreter's stack is never deeper than the code that asked.
 *
 * <p>Two pieces of state are the generator's own, and they are the reason a step is not just a call. The
 * value a {@code return} hands out is kept as a <em>result</em>: {@code gen_iternext} lets a {@code for}
 * loop end on it with no exception at all, while {@code send} turns it into a {@code StopIteration} for
 * the caller that asked. And the exception a clause of this generator's own is running for is put at the
 * head of the thread's handler chain for the length of a step and taken back off, which is what lets a
 * generator parked inside an {@code except} keep that clause's exception across a yield without the
 * caller's handler noticing — and without the caller's handler being what a {@code throw} chains onto.
 *
 * <p>{@code yield from} is not here: the {@code SEND} and {@code END_SEND} family, the delegated iterator
 * {@code gi_yieldfrom} names, and the half of {@code close} and {@code throw} that walks into it all
 * belong to it. A body that uses it fails at {@code YIELD_VALUE} rather than answering wrongly.
 */
public final class PyGenerator extends PyIterator {

    /**
     * CPython's {@code gi_frame_state}, without the name it keeps for a frame whose storage is empty.
     *
     * <p>{@code FRAME_COMPLETED} and {@code FRAME_CLEARED} answer every question Python can ask the same
     * way — {@code gi_frame} is {@code None}, a step does nothing, {@code close} says nothing — and the
     * difference is whether {@code localsplus} still holds references. Dropping them is {@link
     * Frame#clearLocals()}'s business and the frame's own flag records it, so the driver has one state
     * less to keep straight.
     */
    enum State {
        CREATED,
        SUSPENDED,
        EXECUTING,
        COMPLETED
    }

    /** The five names CPython's {@code gen_getsetlist} gives no setter, and so never accepts a write to. */
    private static final String[] READ_ONLY = {
        "gi_frame", "gi_code", "gi_running", "gi_suspended", "gi_yieldfrom"
    };

    /** The table CPython's {@code gen_methods} is: three methods, and the argument counts they check. */
    private static final Members MEMBERS = Members.of(PyType.GENERATOR)
            .addOne("send", (self, argument) -> ((PyGenerator) self).send(argument))
            .add(
                    "throw",
                    (self, args, kwNames) -> {
                        // METH_FASTCALL with no keywords: the calling convention refuses a keyword before
                        // the method sees the arguments, and the count check is the legacy one that names
                        // the method bare and counts only what arrived positionally.
                        Arguments.noKeywords("generator.throw()", kwNames);
                        Arguments.between("throw", args, kwNames, 1, 3);
                        return ((PyGenerator) self).throwIn(args);
                    })
            .addNoArgument("close", self -> ((PyGenerator) self).close());

    private final Frame frame;
    private State state = State.CREATED;

    /**
     * {@code gi_exc_state.exc_value}: the exception a clause of this generator's own is running for.
     *
     * <p>It is a record of the thread's handler chain rather than part of it: {@link #step} puts it at the
     * head for the length of a run and reads it back out, which is the whole of how a generator parked
     * inside a handler keeps that handler's exception across a yield.
     */
    private PyObject handled = PyNone.INSTANCE;

    /**
     * The value a step returned, kept until the entry point that reads it as a {@code StopIteration} takes
     * it — which is CPython's {@code result}, handed to one caller and cleared.
     *
     * <p>Null says there is nothing to read: either the last step yielded, or it ended a generator that had
     * already finished, and {@code next()} has to invent the bare exception for itself.
     */
    private PyObject returned;

    private PyObject name;
    private PyObject qualName;

    /**
     * Take over a frame an ordinary call has just parked, as {@code RETURN_GENERATOR} does.
     *
     * <p>CPython copies the call's frame into the generator, because the original lives on a stack it is
     * about to pop; here the frame is a heap object already, so what is adopted is the one the call made —
     * with its arguments bound, its cells shared, and its own caller dropped along with the call.
     */
    PyGenerator(Frame frame) {
        super(PyType.GENERATOR);
        this.frame = frame;
        frame.generator = this;
        // _Py_MakeCoro has no names to give the generator, so both come off the code object.
        this.name = PyStr.of(frame.code().name());
        this.qualName =
                frame.code().qualName() == null
                        ? this.name
                        : PyStr.of(frame.code().qualName());
    }

    /** The activation this generator owns, for the driver and for tests that read it directly. */
    Frame frame() {
        return frame;
    }

    State state() {
        return state;
    }

    // --- driving -------------------------------------------------------------------------

    /**
     * One run of the frame: CPython's {@code gen_send_ex2}, from the refusals made before entering to the
     * state left behind.
     *
     * <p>The argument is pushed onto the value stack the parked frame left empty, and the instruction it
     * waits on reads it — which is why a {@code next()} has to push {@code None} rather than nothing, and
     * why CPython's NULL argument is kept distinct from {@code None} all the way to the answer: a step that
     * ends a generator through {@code next()} leaves no exception behind, while {@code send} makes one out
     * of the same result.
     *
     * @param arg what the yield expression is worth, or null for {@code next()}
     * @param thrown the exception in flight, for a step entered by {@code throw} at CPython's {@code
     *     error:} label instead of at the parked instruction
     * @return the value the body yielded, or null when it returned and the iteration is over
     */
    private PyObject step(PyObject arg, PyRaise thrown) {
        if (state == State.CREATED && arg != null && arg != PyNone.INSTANCE) {
            // Before the frame is entered, so none of the refusal shows in it.
            throw Slots.typeError("can't send non-None value to a just-started generator");
        }
        if (state == State.EXECUTING) {
            throw Slots.valueError("generator already executing");
        }
        if (state == State.COMPLETED) {
            // There is no frame to enter, and CPython answers three ways depending on who asked.
            returned = null;
            if (thrown != null) {
                // PYGEN_ERROR with nothing set: the thrown error travels on exactly as it arrived, with
                // no context taken from the generator that is not there any more.
                throw thrown;
            }
            // send() of anything, including None, gets a bare StopIteration out of this null; next()
            // gets one invented by builtin_next, which is the same exception by the same route.
            return null;
        }

        // A step that runs the frame leaves no record of the one before it: CPython's result belonged to
        // the call that made it, whoever that was.
        returned = null;
        frame.unpark();
        frame.push(arg == null ? PyNone.INSTANCE : arg);
        PyRuntime.openHandlerItem(handled);
        if (thrown != null) {
            // _PyErr_ChainStackItem, once the swap has happened: the context is this generator's own
            // clause and nothing wider. An empty record chains nothing at all — even when the caller is
            // standing in a handler of its own, which the walk that every other error takes would find.
            thrown =
                    PyRuntime.handlerValue() == PyNone.INSTANCE
                            ? PyRaise.reraise(thrown.value())
                            : PyRaise.of(thrown.value());
        }
        state = State.EXECUTING;
        PyObject result;
        try {
            result = thrown == null ? Interpreter.run(frame) : Interpreter.enter(frame, thrown);
        } catch (PyRaise escaping) {
            // The loop popped the frame on its way out — clear_gen_frame — and a body that returned ends
            // the same way: there is no frame left to enter again whichever exit it took.
            state = State.COMPLETED;
            throw escaping;
        } finally {
            handled = PyRuntime.closeHandlerItem();
        }
        if (frame.isParked()) {
            state = State.SUSPENDED;
            return result;
        }
        state = State.COMPLETED;
        returned = result;
        return null;
    }

    /** {@code next(gen)} at the frame: the yielded value, or null when the generator has run out. */
    @Override
    public PyObject nextOrNull() {
        return step(null, null);
    }

    /** {@code gen.send(value)}, which is the one entry point that takes a returned value as an error. */
    private PyObject send(PyObject argument) {
        PyObject yielded = step(argument, null);
        if (yielded != null) {
            return yielded;
        }
        throw returnedAsStopIteration();
    }

    /**
     * The {@code StopIteration} a completed generator is worth to a caller that asked for an exception.
     *
     * <p>{@code gen_send_ex} and {@code gen_iternext} build it the same two ways, and the two are not
     * interchangeable: {@code PyErr_SetNone} makes a bare one and chains it onto whatever the caller is
     * handling, while {@code _PyGen_SetStopIterationValue} <em>calls the type</em> with the value and sets
     * the result with {@code PyErr_SetRaisedException}, so {@code StopIteration((1, 2)).value} is the
     * tuple and not its first item, and the context stays {@code None}.
     *
     * @return the exception to raise, or null where CPython leaves no exception set at all
     */
    private PyRaise returnedAsStopIteration() {
        PyObject value = takeReturned();
        if (value == null || value == PyNone.INSTANCE) {
            return PyRaise.raise(ExceptionTypes.STOP_ITERATION);
        }
        return PyRaise.reraise(
                (PyBaseException)
                        Slots.call(
                                ExceptionTypes.STOP_ITERATION,
                                new PyObject[] {value},
                                null));
    }

    /**
     * {@code gen_iternext}'s answer to a step that ended the iteration: the exception it left set.
     *
     * <p>Null is CPython's "returned NULL without raising an error", which {@code FOR_ITER} takes as the
     * end of the loop and {@code next()} as a reason to make a bare {@code StopIteration} of its own — the
     * one case where that builtin's exception has no value and no args.
     */
    @Override
    public PyBaseException exhaustion() {
        PyObject value = takeReturned();
        if (value == null || value == PyNone.INSTANCE) {
            return null;
        }
        return (PyBaseException)
                Slots.call(ExceptionTypes.STOP_ITERATION, new PyObject[] {value}, null);
    }

    /** {@code gen_close}, which has an answer of its own to give and usually has nothing to say. */
    private PyObject close() {
        if (state == State.CREATED) {
            // The body never entered, so there is nothing to run: not even a finally.
            state = State.COMPLETED;
            frame.finish();
            frame.clearLocals();
            handled = PyNone.INSTANCE;
            return PyNone.INSTANCE;
        }
        if (state == State.COMPLETED) {
            return PyNone.INSTANCE;
        }
        // PyErr_SetNone happens before the swap, so this exception's context is the caller's handler;
        // _PyErr_ChainStackItem then overwrites it with this generator's own, if it has one.
        //
        // CPython skips all of this when the frame waits on a RESUME whose only open block is the
        // implicit one: nothing can catch the exit there, so it finishes the frame instead of entering it.
        // The answer is the same either way — the exit leaves, close swallows it, the frame is over — and
        // this is the route that also works for a body parked inside a clause of its own.
        PyObject yielded;
        try {
            yielded = step(PyNone.INSTANCE, PyRaise.raise(ExceptionTypes.GENERATOR_EXIT));
        } catch (PyRaise escaped) {
            if (escaped.value().matches(ExceptionTypes.GENERATOR_EXIT)) {
                // The body let the exit out, which is what closing means and is not worth reporting.
                return PyNone.INSTANCE;
            }
            throw escaped;
        }
        if (yielded != null) {
            throw Slots.runtimeError("generator ignored GeneratorExit");
        }
        // A body that answered the exit with a return: gen_send_ex raises that as a StopIteration and
        // _PyGen_FetchStopIterationValue takes the value back out of it, so close() answers with a value
        // rather than with nothing.
        PyObject value = takeReturned();
        return value == null ? PyNone.INSTANCE : value;
    }

    /**
     * {@code _PyGen_Finalize}, for the one caller that reaches it from Python: {@code frame.clear()} on a
     * frame this generator owns and is done with.
     *
     * <p>CPython runs it as a deallocator would — saving the exception in flight, turning anything the
     * close raises into an unraisable report, restoring what it saved. There is no unraisable report here,
     * so what a body that will not close quietly raises travels out to the person who cleared the frame.
     */
    void finalizeFromClear() {
        close();
    }

    /**
     * {@code gen.throw}, which is {@code PyErr_Restore} of its arguments and then a step entered with an
     * exception already in flight.
     *
     * <p>The normalization is {@code raise}'s and arrives in the same order: a class is instantiated, an
     * instance adopts its own traceback when the call did not name one, and anything else is refused by
     * naming the type of what was given rather than what it should have been.
     */
    private PyObject throwIn(PyObject[] arguments) {
        PyObject type = arguments[0];
        PyObject value = arguments.length >= 2 ? arguments[1] : null;
        PyObject trace = arguments.length == 3 ? arguments[2] : null;
        if (trace == PyNone.INSTANCE) {
            trace = null;
        } else if (trace != null && !(trace instanceof PyTraceback)) {
            throw Slots.typeError("throw() third argument must be a traceback object");
        }
        PyBaseException thrown;
        if (type instanceof PyType kind && kind.matches(ExceptionTypes.BASE_EXCEPTION)) {
            thrown = makeException(kind, value);
        } else if (type instanceof PyBaseException instance) {
            if (value != null && value != PyNone.INSTANCE) {
                throw Slots.typeError("instance exception may not have a separate value");
            }
            thrown = instance;
            if (trace == null && thrown.traceback() instanceof PyTraceback carried) {
                trace = carried;
            }
        } else {
            throw Slots.typeError(
                    "exceptions must be classes or instances deriving from BaseException, not %s",
                    type.getType().name());
        }
        if (trace != null) {
            thrown.setTraceback(trace);
        }
        PyObject yielded = step(PyNone.INSTANCE, PyRaise.reraise(thrown));
        if (yielded != null) {
            return yielded;
        }
        // The body answered a thrown exception by returning: gen_send_ex makes that a StopIteration too,
        // and it is the caller of throw() that sees it.
        throw returnedAsStopIteration();
    }

    /**
     * {@code _PyErr_CreateException}: a class thrown with a value beside it is called with that value.
     *
     * <p>The value is the argument list when it is already a tuple, which is what makes {@code
     * throw(KeyError, ("a", "b"))} build a {@code KeyError} with two args rather than one that holds a
     * tuple.
     */
    private static PyBaseException makeException(PyType kind, PyObject value) {
        if (value instanceof PyBaseException instance && instance.getType().matches(kind)) {
            return instance;
        }
        PyObject[] arguments = new PyObject[0];
        if (value instanceof PyTuple items) {
            arguments = items.getArray();
        } else if (value != null) {
            arguments = new PyObject[] {value};
        }
        PyObject made = Slots.call(kind, arguments, null);
        if (!(made instanceof PyBaseException exception)) {
            throw Slots.typeError(
                    "%s() should return an exception, got %s", kind.name(), made.getType().name());
        }
        return exception;
    }

    /** The returned value, taken once: CPython's {@code result} is used by one caller and then cleared. */
    private PyObject takeReturned() {
        PyObject value = returned;
        returned = null;
        return value;
    }

    // --- what Python reads ---------------------------------------------------------------

    @Override
    public PyObject getAttr(String attribute) {
        return switch (attribute) {
            case "__name__" -> name;
            case "__qualname__" -> qualName;
            // PyGen_Type declares no tp_doc, so the name inherited from object has nothing to show.
            case "__doc__" -> PyNone.INSTANCE;
            case "__class__" -> getType();
            // There is no `yield from`, so nothing is ever being delegated to.
            case "gi_yieldfrom" -> PyNone.INSTANCE;
            case "gi_running" -> PyBool.of(state == State.EXECUTING);
            case "gi_suspended" -> PyBool.of(state == State.SUSPENDED);
            // A finished generator has no frame for Python to reach, however it finished — although the
            // code object stays, because it belongs to the generator and not to the frame.
            case "gi_frame" ->
                    state == State.COMPLETED ? PyNone.INSTANCE : frame.frameObject();
            case "gi_code" -> (PyObject) frame.code();
            default -> Members.find(this, attribute);
        };
    }

    /**
     * {@code gen.name = value}: two writable names, five getsets that refuse a write, and no dictionary
     * for anything else.
     *
     * <p>The three refusals are CPython's three kinds of descriptor. A getset with no setter complains
     * about the attribute; a method or {@code __doc__} found in the type has no setter at all, and that is
     * the read-only complaint; a name the type does not publish cannot be stored on a value that has no
     * {@code __dict__}. {@code __name__} and {@code __qualname__} are the only pair with a setter, and
     * theirs is a complaint about the value rather than the name.
     */
    @Override
    public PyObject setAttr(String attribute, PyObject value) {
        if (attribute.equals("__name__") || attribute.equals("__qualname__")) {
            setName(attribute, value);
            return PyNone.INSTANCE;
        }
        throw refusal(attribute);
    }

    /**
     * {@code del gen.name}, which answers as a write does everywhere except the two names with a setter:
     * {@code gen_set_name} is given for the deletion too, and it reads a deleted value as "not a string".
     */
    @Override
    public PyObject delAttr(String attribute) {
        if (attribute.equals("__name__") || attribute.equals("__qualname__")) {
            setName(attribute, null);
            return PyNone.INSTANCE;
        }
        throw refusal(attribute);
    }

    private void setName(String attribute, PyObject value) {
        if (!(value instanceof PyStr text)) {
            // The same sentence for a deletion, because gen_set_name is what runs for that too and it
            // reads the absent value as "not a string".
            throw Slots.typeError(attribute + " must be set to a string object");
        }
        if (attribute.equals("__name__")) {
            name = text;
        } else {
            qualName = text;
        }
    }

    /** The complaint for a name this type will not take, chosen by what kind of descriptor holds it. */
    private PyRaise refusal(String attribute) {
        for (String name : READ_ONLY) {
            if (name.equals(attribute)) {
                return Slots.attributeError(
                        "attribute '" + attribute + "' of 'generator' objects is not writable");
            }
        }
        if (MEMBERS.defines(attribute) || attribute.equals("__doc__")) {
            return Slots.attributeError(
                    "'generator' object attribute '" + attribute + "' is read-only");
        }
        return Slots.attributeError(
                "'generator' object has no attribute '" + attribute
                        + "' and no __dict__ for setting new attributes");
    }

    @Override
    public long hash() {
        return System.identityHashCode(this);
    }

    @Override
    public String repr() {
        // gen_repr, with this object's identity where CPython prints a memory address.
        return "<generator object "
                + qualName
                + " at 0x"
                + Integer.toHexString(System.identityHashCode(this))
                + ">";
    }
}
