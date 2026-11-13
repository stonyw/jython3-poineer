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
 * <p>A delegation is the same frame with one more thing on its value stack. {@code yield from} leaves the
 * iterator it stands on under the value it hands out, so a step of this generator is a step of that
 * iterator, {@code gi_yieldfrom} names it, and the {@code send}, {@code throw} and {@code close} below are
 * the three that ask it before they ask the body. What ends a delegation is a {@code StopIteration}, and the
 * value it carries is the value of the expression — which is why the driver's {@code SEND} jumps rather than
 * stepping, and why the half of {@code close} that closes a delegate runs before the exit is thrown in.
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
        /** {@code FRAME_SUSPENDED_YIELD_FROM}: parked on a delegation rather than on a plain yield. */
        SUSPENDED_YIELD_FROM,
        EXECUTING,
        COMPLETED
    }

    /**
     * Which of the three frame-owning objects this one is.
     *
     * <p>The frame machine is the same for all three — that is the whole reason {@code yield},
     * {@code await} and {@code async for} share one driver — and only the name used in a refusal or in
     * a representation, and what a step that finds the body already finished means, differ. A coroutine
     * is the one that is never iterated: it is {@code send}, {@code throw}, {@code close} and
     * {@code __await__}, and the two objects that own coroutines add those on top of this engine.
     */
    enum Kind {
        GENERATOR("generator", "generator"),
        COROUTINE("coroutine", "coroutine"),
        ASYNC_GENERATOR("async_generator", "async generator");

        /** The name in a representation, which for an async generator keeps the underscore. */
        final String word;

        /** The same name in a sentence, where CPython writes the two words apart. */
        final String speech;

        Kind(String word, String speech) {
            this.word = word;
            this.speech = speech;
        }
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
                        Arguments.noKeywords(
                                ((PyGenerator) self).kind.word + ".throw()", kwNames);
                        Arguments.between("throw", args, kwNames, 1, 3);
                        return ((PyGenerator) self).throwIn(args);
                    })
            .addNoArgument("close", self -> ((PyGenerator) self).close());

    final Kind kind;
    final Frame frame;
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

    PyObject name;
    PyObject qualName;

    /**
     * Take over a frame an ordinary call has just parked, as {@code RETURN_GENERATOR} does.
     *
     * <p>CPython copies the call's frame into the generator, because the original lives on a stack it is
     * about to pop; here the frame is a heap object already, so what is adopted is the one the call made —
     * with its arguments bound, its cells shared, and its own caller dropped along with the call.
     */
    PyGenerator(Frame frame) {
        this(frame, Kind.GENERATOR);
    }

    /**
     * The same adoption, for a coroutine or an async generator.
     *
     * <p>{@code super} still names one of the three types so that a value of any of them is answered by
     * the table of the type it really is; what the kind changes is the wording of the refusals and of
     * the representation, and what {@code send} on a finished body means.
     */
    PyGenerator(Frame frame, Kind kind) {
        super(kind == Kind.COROUTINE
                ? PyType.COROUTINE
                : kind == Kind.ASYNC_GENERATOR ? PyType.ASYNC_GENERATOR : PyType.GENERATOR);
        this.kind = kind;
        this.frame = frame;
        frame.generator = this;
        // _Py_MakeCoro copies the names off the function the call was making, so a generator made after
        // `func.__name__ = ...` answers the new name; only a generator made without a function (none
        // here) falls back on the code object.
        PyFunction owner = frame.function;
        if (owner != null) {
            this.name = owner.coroutineName();
            this.qualName = owner.coroutineQualName();
        } else {
            this.name = PyStr.of(frame.code().name());
            this.qualName =
                    frame.code().qualName() == null
                            ? this.name
                            : PyStr.of(frame.code().qualName());
        }
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
            throw Slots.typeError(
                    "can't send non-None value to a just-started " + kind.speech);
        }
        if (state == State.EXECUTING) {
            throw Slots.valueError(kind.speech + " already executing");
        }
        if (state == State.COMPLETED) {
            // There is no frame to enter, and CPython answers three ways depending on who asked.
            returned = null;
            if (kind == Kind.COROUTINE) {
                // A coroutine is not an iterator: there is no "next" whose answer would be a bare
                // StopIteration, so every reuse — send of anything, or a throw — is a RuntimeError.
                throw Slots.runtimeError("cannot reuse already awaited coroutine");
            }
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
            state = frame.isDelegating() ? State.SUSPENDED_YIELD_FROM : State.SUSPENDED;
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

    /**
     * One step as {@code async_gen_asend_send} asks it: the yielded value, or null when the body
     * returned.
     *
     * <p>An async generator's step is driven through an awaitable rather than asked directly, so the
     * exception the end of a generator is worth is not made here — {@code asend} makes the
     * {@code StopAsyncIteration} instead, and a yield becomes the {@code StopIteration} that says the
     * await is over.
     */
    PyObject stepForAwait(PyObject argument) {
        return step(argument, null);
    }

    /**
     * {@code _gen_throw} as {@code async_gen_athrow_send} asks it: the raw yielded value, or null when
     * the body returned, with the {@code StopIteration} the caller would make left to the caller.
     */
    PyObject throwForAwait(PyObject type, PyObject value, PyObject trace) {
        return thrown(type, value, trace);
    }

    /** {@code gen.send(value)}, which is the one entry point that takes a returned value as an error. */
    PyObject send(PyObject argument) {
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
    PyObject close() {
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
        PyRaise exit = PyRaise.raise(ExceptionTypes.GENERATOR_EXIT);
        PyObject delegate = yieldFrom();
        if (delegate != null) {
            // The delegate is closed first, and the error it answers with is the exception this frame is
            // entered with instead of an exit it never got to be: a close is not owed a GeneratorExit that
            // was never thrown. The frame is not linked under anything for the walk — CPython asks the
            // delegate as it stands, so a delegate that looks back at this generator finds no new entry —
            // but it is marked as running, which is what makes a step asked of this generator during the
            // close the usual refusal rather than a re-entry.
            State parked = state;
            state = State.EXECUTING;
            PyRaise refused = closeDelegate(delegate);
            state = parked;
            if (refused != null) {
                exit = refused;
            }
        }
        PyObject yielded;
        try {
            yielded = step(PyNone.INSTANCE, exit);
        } catch (PyRaise escaped) {
            if (escaped.value().matches(ExceptionTypes.GENERATOR_EXIT)) {
                // The body let the exit out, which is what closing means and is not worth reporting.
                return PyNone.INSTANCE;
            }
            throw escaped;
        }
        if (yielded != null) {
            throw Slots.runtimeError(kind.speech + " ignored GeneratorExit");
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

    /** {@code gen_throw}: the argument count the legacy signature checks, and the three it hands over. */
    PyObject throwIn(PyObject[] arguments) {
        if (arguments.length == 3) {
            // `throw(type, value, traceback)` is deprecated at the call site, before the walk into a
            // delegate, so a warning promoted to an error stops the throw where the reference stops it.
            PyObject warnings = PyRuntime.importModule("warnings");
            Slots.call(
                    Slots.getAttr(warnings, "warn"),
                    new PyObject[] {
                        PyStr.of("the (type, exc, tb) signature of throw() is deprecated, "
                                + "use the single-arg signature instead."),
                        ExceptionTypes.DEPRECATION_WARNING
                    },
                    null);
        }
        PyObject type = arguments[0];
        PyObject value = arguments.length >= 2 ? arguments[1] : null;
        PyObject trace = arguments.length == 3 ? arguments[2] : null;
        PyObject yielded = thrown(type, value, trace);
        if (yielded != null) {
            return yielded;
        }
        // The body answered a thrown exception by returning: gen_send_ex makes that a StopIteration too,
        // and it is the caller of throw() that sees it.
        throw returnedAsStopIteration();
    }

    /**
     * CPython's {@code _gen_throw}: the walk into the delegate that comes first, and the throw into this
     * generator's own frame that follows it.
     *
     * <p>Two things about the walk are what a script sees. The arguments travel to the delegate exactly as
     * this method received them, because only the throw into this frame normalizes them — so a plain
     * iterator's {@code throw} is called with one argument for {@code throw(ValueError("x"))} and not with
     * a class and an instance. And whatever the delegate answers with is what this generator answers with,
     * unless it answers with a failure: then the error the delegate made is thrown into the frame that
     * delegated, which is what runs a {@code finally} written around the {@code yield from} and puts both
     * frames in the report.
     *
     * @return the value this generator yields, or null when it ended and the caller must make the
     *     {@code StopIteration} that says so
     */
    private PyObject thrown(PyObject type, PyObject value, PyObject trace) {
        PyObject delegate = yieldFrom();
        if (delegate == null) {
            return throwHere(type, value, trace);
        }
        if (isGeneratorExit(type)) {
            // The exit a `close` sends and the exit a person throw by name take the same route: the
            // delegate is closed first, and what it answers with — rather than the exit — is what comes in.
            PyRaise refused = closeDelegate(delegate);
            return refused != null ? step(PyNone.INSTANCE, refused) : throwHere(type, value, trace);
        }
        PyGenerator inner = delegate instanceof PyGenerator cast ? cast : null;
        PyObject method = inner == null ? Slots.findAttr(delegate, "throw") : null;
        if (inner == null && method == null) {
            // A delegate with no throw of its own is one the exception goes back over: this frame is
            // entered with the arguments as they were given, at the yield that parked the delegation.
            return throwHere(type, value, trace);
        }
        State parked = state;
        Frame asked = Interpreter.enterDelegation(frame);
        PyObject answered = null;
        PyRaise failed = null;
        try {
            state = State.EXECUTING;
            if (inner != null) {
                answered = inner.thrown(type, value, trace);
                if (answered == null) {
                    // The delegate returned: its StopIteration is thrown in here, and this frame's own
                    // CLEANUP_THROW is what turns it into the value of the expression.
                    failed = inner.returnedAsStopIteration();
                }
            } else {
                answered = Slots.call(method, throwArguments(type, value, trace), null);
            }
        } catch (PyRaise escaping) {
            failed = escaping;
        } finally {
            state = parked;
            Interpreter.leaveDelegation(asked);
        }
        if (answered != null) {
            return answered;
        }
        return step(PyNone.INSTANCE, failed);
    }

    /**
     * The half of {@code _gen_throw} that reaches this frame's own body: {@code PyErr_Restore} of the
     * arguments and a step entered with an exception already in flight.
     *
     * <p>The normalization is {@code raise}'s and arrives in the same order: a class is instantiated, an
     * instance adopts its own traceback when the call did not name one, and anything else is refused by
     * naming the type of what was given rather than what it should have been.
     */
    private PyObject throwHere(PyObject type, PyObject value, PyObject trace) {
        if (trace == PyNone.INSTANCE) {
            trace = null;
        } else if (trace != null && !(trace instanceof PyTraceback)) {
            throw Slots.typeError("throw() third argument must be a traceback object");
        }
        PyBaseException thrown;
        if (type instanceof PyType kind && kind.matches(ExceptionTypes.BASE_EXCEPTION)) {
            try {
                thrown = makeException(kind, value);
            } catch (PyRaise refused) {
                // A class whose __new__ answers something other than an exception has not made one, and
                // the reference leaves that failure in flight rather than reporting it from throw(): the
                // frame is entered with it, so the body meets it at its yield (or at its first line) and
                // the generator ends the way the body's own handlers decide.
                return step(PyNone.INSTANCE, refused);
            }
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
        return step(PyNone.INSTANCE, PyRaise.reraise(thrown));
    }

    /**
     * CPython's {@code gen_close_iter}, for the delegate of a {@code yield from}: the close it is owed, and
     * the error it answers with where it has one.
     *
     * <p>A generator is closed through its own {@code close}, and any other delegate is asked for a
     * {@code close} attribute — one it may not have, which is not a complaint but the answer that nothing
     * needs closing. What the call raises is kept rather than reported: it is the exception the delegating
     * frame is entered with.
     */
    private static PyRaise closeDelegate(PyObject delegate) {
        try {
            if (delegate instanceof PyGenerator inner) {
                inner.close();
                return null;
            }
            PyObject method = Slots.findAttr(delegate, "close");
            if (method == null) {
                return null;
            }
            Slots.call(method, new PyObject[0], null);
            return null;
        } catch (PyRaise refused) {
            return refused;
        }
    }

    /** Whether {@code typ}, still a class or an instance, is the exit a {@code close} carries. */
    private static boolean isGeneratorExit(PyObject type) {
        if (type instanceof PyType kind) {
            return kind.matches(ExceptionTypes.GENERATOR_EXIT);
        }
        return type instanceof PyBaseException error && error.matches(ExceptionTypes.GENERATOR_EXIT);
    }

    /**
     * The arguments a delegate's own {@code throw} is called with, in the shape CPython's {@code
     * PyObject_CallFunctionObjArgs} leaves them: the walk stops at the first one the caller did not give,
     * so a one-argument {@code throw} never sees a second.
     */
    private static PyObject[] throwArguments(PyObject type, PyObject value, PyObject trace) {
        if (trace != null) {
            return new PyObject[] {type, value, trace};
        }
        if (value != null) {
            return new PyObject[] {type, value};
        }
        return new PyObject[] {type};
    }

    /**
     * CPython's {@code _PyGen_yf}: the delegate this generator stands on, or null where it is not standing
     * on one.
     *
     * <p>The answer is the top of the parked frame's value stack, because that is where the loop the
     * compiler wrote keeps the iterator — and it is only the answer while the frame is <em>parked</em> on
     * it. A step of the delegate runs with this generator marked as executing, which is why a delegate that
     * asks {@code gi_yieldfrom} of the generator that delegated to it is told about nothing.
     */
    PyObject yieldFrom() {
        return state == State.SUSPENDED_YIELD_FROM ? frame.peek(0) : null;
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
            // `_PyErr_CreateException` names the thing that was called and the kind of what came back,
            // because a class whose __new__ answers itself is a call that failed to make an exception.
            throw Slots.typeError(
                    "calling %s should have returned an instance of BaseException, not %s",
                    kind.repr(), made.getType().name());
        }
        return exception;
    }

    /** CPython's {@code FRAME_STATE_SUSPENDED}: parked, whether on a yield or on a delegation. */
    boolean isSuspended() {
        return state == State.SUSPENDED || state == State.SUSPENDED_YIELD_FROM;
    }

    /** {@code gi_yieldfrom} as Python reads it: the delegate, or {@code None} where none is stood on. */
    private PyObject delegated() {
        PyObject delegate = yieldFrom();
        return delegate == null ? PyNone.INSTANCE : delegate;
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
            // Only a frame parked on a delegation is standing on one; a step of the delegate runs with this
            // generator marked as executing, and then the answer is the same None.
            case "gi_yieldfrom" -> delegated();
            case "gi_running" -> PyBool.of(state == State.EXECUTING);
            case "gi_suspended" -> PyBool.of(isSuspended());
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

    /**
     * The name this object's {@code repr} prints: the qualname's text rather than its repr, because the
     * reference prints {@code <coroutine object one at 0x…>}, without quotes.
     */
    String displayName() {
        return qualName instanceof PyStr text ? text.getValue() : qualName.str();
    }

    @Override
    public String repr() {
        // gen_repr, with this object's identity where CPython prints a memory address.
        return "<" + kind.word + " object "
                + displayName()
                + " at 0x"
                + Integer.toHexString(System.identityHashCode(this))
                + ">";
    }
}
