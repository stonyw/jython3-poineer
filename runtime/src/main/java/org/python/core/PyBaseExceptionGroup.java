package org.python.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * An exception group: one exception that carries several, which is what {@code except*} selects over.
 *
 * <p>Which of the two classes a value belongs to is not the caller's decision. CPython promotes
 * {@code BaseExceptionGroup("m", [ValueError()])} to {@code ExceptionGroup} because every child is an
 * {@code Exception}, and refuses to nest a {@code BaseException} in an {@code ExceptionGroup} at all, so
 * {@code type(g)} is often not the class that was called. {@link #create} keeps the rules in the order
 * {@code BaseExceptionGroup_new} makes them: the two arguments, the sequence, the snapshot of it, its
 * emptiness, each child, then the class — with the keyword refusal last of all, because the creator is
 * the one that sees the named argument and by then the class has already been chosen.
 *
 * <p>Three values are kept and each answers a different question. {@code message} and
 * {@code exceptions} are the group's own, read-only, and {@code exceptions} is always a tuple however
 * the call spelled it. {@code args} is the field every exception has and keeps the arguments exactly as
 * they arrived, so {@code g.args[1] is the_list} holds and a later change to that list shows in
 * {@code g.args} but never in {@code g.exceptions}. {@code repr} is the one place that has to keep up
 * with both: a call that was given a list is still shown with list brackets, over the children the group
 * holds, and a sequence a person wrote shows the {@code repr} it had when the call was made — the
 * snapshot CPython stores as {@code excs_str} because that object can change underneath it.
 *
 * <p>{@code split}, {@code subgroup} and {@code derive} are CPython's recursive three, including the
 * detail that they always build through {@code BaseExceptionGroup}: a subset of {@code Exception}
 * children is therefore read back as an {@code ExceptionGroup}, and a group subclass never gets one of
 * itself. The metadata a part inherits — traceback, context, cause and a copy of the notes — is copied
 * as CPython copies it, which leaves {@code __suppress_context__} alone rather than re-deciding it.
 *
 * <p>Two of CPython's own are not modelled. There is no recursion guard on the walk of a group tree, so
 * a group that contains itself overflows the Java stack where CPython raises {@code RecursionError}; and
 * reading {@code message} or {@code exceptions} from the <em>type</em> gives nothing, as it already does
 * for {@code BaseException.args}, where CPython gives the {@code member_descriptor} kept there.
 */
public class PyBaseExceptionGroup extends PyBaseException {

    private final PyStr message;

    /** {@code e.exceptions}: the group's own, always a tuple, however the call spelled it. */
    private final PyTuple exceptions;

    /** {@code excs_str}: the {@code repr} of the sequence as it arrived, or null for a list or a tuple. */
    private final PyObject snapshot;

    private PyBaseExceptionGroup(
            PyType kind, PyObject[] values, PyStr message, PyTuple exceptions, PyObject snapshot) {
        super(kind, values);
        this.message = message;
        this.exceptions = exceptions;
        this.snapshot = snapshot;
    }

    /**
     * {@code BaseExceptionGroup_new} for the class {@code kind} over the arguments {@code values}.
     *
     * @param keywords whether the call named an argument, which is refused only once every check below
     *     has passed, and so names the class the value was made as rather than the one called
     */
    static PyObject create(PyType kind, PyObject[] values, boolean keywords) {
        if (values.length != 2) {
            throw Slots.typeError(
                    "BaseExceptionGroup.__new__() takes exactly 2 arguments (%d given)",
                    values.length);
        }
        if (!(values[0] instanceof PyStr given)) {
            throw Slots.typeError(
                    "BaseExceptionGroup.__new__() argument 1 must be str, not %s",
                    values[0].getType().name());
        }
        if (!Sequences.isSequence(values[1])) {
            throw Slots.typeError("second argument (exceptions) must be a sequence");
        }
        // The snapshot is of the object the call handed over, taken before it is copied and before any
        // child is checked, because that is the repr CPython keeps for repr(group) afterwards.
        PyObject snapshot =
                values[1] instanceof PyList || values[1] instanceof PyTuple
                        ? null
                        : PyStr.of(values[1].repr());
        PyObject[] handed = elements(values[1]).toArray(new PyObject[0]);
        if (handed.length == 0) {
            throw Slots.valueError("second argument (exceptions) must be a non-empty sequence");
        }
        boolean nestsBase = false;
        for (int i = 0; i < handed.length; i++) {
            if (!(handed[i] instanceof PyBaseException)) {
                throw Slots.valueError(
                        "Item %d of second argument (exceptions) is not an exception", i);
            }
            nestsBase |=
                    !((PyBaseException) handed[i])
                            .exceptionType()
                            .matches(ExceptionTypes.EXCEPTION);
        }

        PyType made = kind;
        if (kind == ExceptionTypes.EXCEPTION_GROUP) {
            if (nestsBase) {
                throw Slots.typeError("Cannot nest BaseExceptions in an ExceptionGroup");
            }
        } else if (kind == ExceptionTypes.BASE_EXCEPTION_GROUP) {
            if (!nestsBase) {
                // Every child is an Exception, so the narrower class answers for them.
                made = ExceptionTypes.EXCEPTION_GROUP;
            }
        } else if (nestsBase && kind.matches(ExceptionTypes.EXCEPTION)) {
            // A class a person wrote is never promoted, and one that is an Exception refuses.
            throw Slots.typeError("Cannot nest BaseExceptions in '%s'", kind.name());
        }
        if (keywords) {
            throw Slots.typeError("%s() takes no keyword arguments", made.name());
        }
        return new PyBaseExceptionGroup(
                made, values, given, PyTuple.of(handed), snapshot);
    }

    /**
     * {@code PySequence_List}'s walk of a sequence, in CPython's order: an iterator when the value has
     * one, and the indexed protocol otherwise.
     *
     * <p>The order is visible: a class that answers both {@code __iter__} and {@code __getitem__} is read
     * by {@code __iter__}, which is the same choice {@code list(x)} makes.
     */
    private static List<PyObject> elements(PyObject sequence) {
        List<PyObject> items = new ArrayList<>();
        PyIterator walking = Slots.iterator(sequence);
        for (PyObject item = walking.nextOrNull(); item != null; item = walking.nextOrNull()) {
            items.add(item);
        }
        return items;
    }

    /** {@code e.exceptions} for Java, which is what the report walks to build a group tree. */
    PyTuple children() {
        return exceptions;
    }

    /**
     * {@code e.message} and {@code e.exceptions}, ahead of the value's own dictionary as CPython's
     * read-only members are, and {@code ExceptionGroup}'s {@code __module__} with them.
     *
     * <p>{@code __module__} is the one answer here that follows from how CPython declares the two
     * classes: {@code ExceptionGroup} is made by {@code PyErr_NewException}, which files the name in the
     * type's dictionary where an instance can find it, while {@code BaseExceptionGroup} is a static type
     * that keeps no {@code __module__} an instance can read. So a group that was promoted answers
     * {@code 'builtins'} and one holding a {@code KeyboardInterrupt} refuses.
     */
    @Override
    public PyObject getAttr(String name) {
        return switch (name) {
            case "message" -> message;
            case "exceptions" -> exceptions;
            case "__module__" ->
                    exceptionType() == ExceptionTypes.EXCEPTION_GROUP
                            ? PyStr.of("builtins")
                            : super.getAttr(name);
            default -> super.getAttr(name);
        };
    }

    /** {@code e.message = value} and {@code e.exceptions = value}: both refused, as CPython's are. */
    @Override
    public PyObject setAttr(String name, PyObject value) {
        if (refusesMember(name, "__setattr__")) {
            throw Slots.attributeError("readonly attribute");
        }
        return super.setAttr(name, value);
    }

    @Override
    public PyObject delAttr(String name) {
        if (refusesMember(name, "__delattr__")) {
            throw Slots.attributeError("readonly attribute");
        }
        return super.delAttr(name);
    }

    /**
     * Whether {@code name} is one of the two read-only members, which is decided before the class has
     * been asked anything only when it declares no hook of its own for this assignment.
     *
     * <p>A {@code __setattr__} or {@code __delattr__} written in Python replaces the whole lookup, as
     * CPython's does, and takes the step that the member would have refused.
     */
    private boolean refusesMember(String name, String hook) {
        return (name.equals("message") || name.equals("exceptions"))
                && !(Specials.lookUp(exceptionType(), hook) instanceof PyFunction);
    }

    /**
     * {@code str(g)}: the message and how many exceptions it carries, singular below two.
     *
     * <p>{@code BaseExceptionGroup_str} counts {@code self->excs} rather than {@code args}, which is why
     * a later change to the list the call was given leaves this reading what the group holds.
     */
    @Override
    public String str() {
        PyObject declared = displayed("__str__");
        if (declared != null) {
            return display(declared, "__str__");
        }
        int count = exceptions.size();
        return message.str() + " (" + count + " sub-exception" + (count > 1 ? "s" : "") + ")";
    }

    /** {@code repr(g)}: the message and the children, in whichever of the three shapes CPython uses. */
    @Override
    public String repr() {
        PyObject declared = displayed("__repr__");
        if (declared != null) {
            return display(declared, "__repr__");
        }
        return exceptionType().name() + "(" + message.repr() + ", " + shown() + ")";
    }

    private String shown() {
        if (snapshot != null) {
            return snapshot.str();
        }
        if (args().size() == 2 && args().at(1) instanceof PyList) {
            // Accurate about the children, shaped like the argument that was given: CPython's own fix
            // for a repr that used to read args[1] and go out of sync with exceptions.
            return PyList.of(exceptions.getArray()).repr();
        }
        return exceptions.repr();
    }

    // --- the three methods a group answers with ------------------------------------------------

    /** {@code BaseExceptionGroup_derive}: always built through {@code BaseExceptionGroup}, not this class. */
    static PyObject derive(PyObject self, PyObject excs) {
        PyBaseExceptionGroup group = cast(self, "derive");
        return Slots.call(
                ExceptionTypes.BASE_EXCEPTION_GROUP, new PyObject[] {group.message, excs}, null);
    }

    /** {@code BaseExceptionGroup_split}: the matching part and the rest, either of which may be None. */
    static PyTuple split(PyObject self, PyObject matcher) {
        PyBaseExceptionGroup group = cast(self, "split");
        Parts parts = walk(group, matcherOf(matcher), true);
        return PyTuple.of(orNone(parts.match), orNone(parts.rest));
    }

    /** {@code BaseExceptionGroup_subgroup}: the matching part alone, or None. */
    static PyObject subgroup(PyObject self, PyObject matcher) {
        PyBaseExceptionGroup group = cast(self, "subgroup");
        return orNone(walk(group, matcherOf(matcher), false).match);
    }

    private static PyBaseExceptionGroup cast(PyObject self, String name) {
        if (!(self instanceof PyBaseExceptionGroup group)) {
            throw Slots.typeError(
                    "descriptor '%s' for '%s' objects doesn't apply to a '%s' object",
                    name, ExceptionTypes.BASE_EXCEPTION_GROUP.name(), self.getType().name());
        }
        return group;
    }

    private static PyObject orNone(PyObject value) {
        return value == null ? PyNone.INSTANCE : value;
    }

    /**
     * {@code get_matcher_type}, reduced to the one question {@code
     * exceptiongroup_split_check_match} answers.
     *
     * <p>A class is a type to match against rather than a predicate to call, which is the whole of why
     * {@code split(ValueError)} and {@code split(lambda e: True)} are two different things.
     */
    private static Predicate<PyObject> matcherOf(PyObject matcher) {
        if (Slots.isCallable(matcher) && !(matcher instanceof PyType)) {
            return value -> Slots.truth(Slots.call(matcher, new PyObject[] {value}, null));
        }
        if (isExceptionClass(matcher)
                || (matcher instanceof PyTuple exact && allExceptionClasses(exact))) {
            return value -> givenExceptionMatches(value, matcher);
        }
        throw Slots.typeError(
                "expected an exception type, a tuple of exception types, "
                        + "or a callable (other than a class)");
    }

    private static boolean isExceptionClass(PyObject value) {
        return value instanceof PyType kind && kind.matches(ExceptionTypes.BASE_EXCEPTION);
    }

    private static boolean allExceptionClasses(PyTuple items) {
        for (PyObject item : items.getArray()) {
            if (!isExceptionClass(item)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code PyErr_GivenExceptionMatches}: a tuple asks each member, an exception instance is asked of
     * its class, and anything else is identity.
     *
     * <p>No group knows anything about groups here, which is why {@code split(ExceptionGroup)} matches a
     * group only when the value <em>is</em> one and reaches a leaf inside it through the walk below
     * instead. The recursion limit CPython puts on the tuple walk is a deviation.
     */
    private static boolean givenExceptionMatches(PyObject value, PyObject clause) {
        if (clause instanceof PyTuple items) {
            for (PyObject each : items.getArray()) {
                if (givenExceptionMatches(value, each)) {
                    return true;
                }
            }
            return false;
        }
        if (!(value instanceof PyBaseException error)) {
            return value == clause;
        }
        return isExceptionClass(clause)
                ? error.exceptionType().matches((PyType) clause)
                : value == clause;
    }

    /** The two halves of a split, either of which CPython leaves null rather than None. */
    private static final class Parts {
        private PyBaseException match;
        private PyBaseException rest;
    }

    /**
     * {@code exceptiongroup_split_recursive}: a full match takes the whole value, a leaf that refuses
     * takes nothing, and a group that only partly matches has each child asked and the answers gathered
     * under a copy of itself.
     */
    private static Parts walk(
            PyBaseException value, Predicate<PyObject> matcher, boolean constructRest) {
        Parts parts = new Parts();
        if (matcher.test(value)) {
            parts.match = value;
            return parts;
        }
        if (!(value instanceof PyBaseExceptionGroup group)) {
            if (constructRest) {
                parts.rest = value;
            }
            return parts;
        }
        List<PyObject> matched = new ArrayList<>();
        List<PyObject> other = constructRest ? new ArrayList<>() : null;
        for (PyObject child : group.exceptions.getArray()) {
            Parts part = walk((PyBaseException) child, matcher, constructRest);
            if (part.match != null) {
                matched.add(part.match);
            }
            if (part.rest != null) {
                other.add(part.rest);
            }
        }
        parts.match = subset(group, PyList.of(matched.toArray(new PyObject[0])));
        if (constructRest) {
            parts.rest = subset(group, PyList.of(other.toArray(new PyObject[0])));
        }
        return parts;
    }

    /**
     * {@code exceptiongroup_subset}: {@code derive} asked of the value itself, and then the original's
     * metadata put on the part as fields rather than as assignments.
     *
     * <p>{@code __cause__} is written through {@link PyBaseException#setCause}, which is what CPython's
     * {@code PyException_SetCause} is: it names the cause and takes the context out of the report in the
     * same move, so a part of a group that was raised from nothing still says {@code
     * __suppress_context__} is true. The notes are copied when they are a sequence and ignored when they
     * are not, because CPython declines to report an old user error from inside a split.
     */
    private static PyBaseException subset(PyBaseExceptionGroup original, PyList selected) {
        if (selected.size() == 0) {
            return null;
        }
        PyObject answer =
                Slots.call(Slots.getAttr(original, "derive"), new PyObject[] {selected}, null);
        if (!(answer instanceof PyBaseExceptionGroup made)) {
            throw Slots.typeError("derive must return an instance of BaseExceptionGroup");
        }
        made.setTraceback(original.traceback());
        made.setContext(original.context());
        made.setCause(original.cause());
        PyObject notes = original.getAttr("__notes__");
        if (notes != null && Sequences.isSequence(notes)) {
            made.setAttr("__notes__", PyList.of(elements(notes).toArray(new PyObject[0])));
        }
        return made;
    }

    // --- what an except* clause asks of a raised value ---------------------------------------------

    /**
     * {@code _PyEval_CheckExceptStarTypeValid}: the two refusals an {@code except*} makes of its types,
     * in the order CPython makes them.
     *
     * <p>A tuple is scanned for the first member that is not an exception class, and then for the first
     * that is a group — so {@code except* (int, ExceptionGroup)} complains about {@code int} and never
     * gets as far as the group it would otherwise refuse.
     */
    static void checkClause(PyObject types) {
        PyObject[] members =
                types instanceof PyTuple exact ? exact.getArray() : new PyObject[] {types};
        for (PyObject member : members) {
            if (!isExceptionClass(member)) {
                throw Slots.typeError(
                        "catching classes that do not inherit from BaseException is not allowed");
            }
        }
        for (PyObject member : members) {
            if (((PyType) member).matches(ExceptionTypes.BASE_EXCEPTION_GROUP)) {
                throw Slots.typeError(
                        "catching ExceptionGroup with except* is not allowed. Use except instead.");
            }
        }
    }

    /**
     * {@code _PyEval_ExceptionGroupMatch}, answered as {@code CHECK_EG_MATCH} wants it: rest first, then
     * match.
     *
     * <p>Nothing matches a {@code None} — the clause under a {@code try} that raised nothing reads both
     * halves as {@code None}, which is what lets the body run with no group at all. A full match takes
     * the whole value, with one exception: a naked exception that matches is wrapped in a group of one,
     * because the rest of the machinery only works over groups. That wrapper is the one group in the
     * runtime whose traceback names a clause rather than a {@code raise}, and it is built here, at the
     * frame testing the clause, as {@code PyTraceBack_Here} builds it.
     *
     * <p>Only a group is split by {@link #walk}, and it is split by calling {@code split} on the value —
     * so a class that writes its own {@code split} is asked for a 2-tuple and refused by name if it
     * answers with anything else.
     */
    static PyObject[] clause(PyObject raised, PyObject types, Frame frame) {
        if (raised == PyNone.INSTANCE) {
            return new PyObject[] {PyNone.INSTANCE, PyNone.INSTANCE};
        }
        PyBaseException error = (PyBaseException) raised;
        if (givenExceptionMatches(error, types)) {
            PyObject match =
                    error instanceof PyBaseExceptionGroup ? error : wrap(error, frame);
            return new PyObject[] {PyNone.INSTANCE, match};
        }
        if (error instanceof PyBaseExceptionGroup group) {
            PyObject pair =
                    Slots.call(Slots.getAttr(group, "split"), new PyObject[] {types}, null);
            if (!(pair instanceof PyTuple) || pair.getType() != PyType.TUPLE) {
                throw Slots.typeError(
                        "%s.split must return a tuple, not %s",
                        group.exceptionType().name(), pair.getType().name());
            }
            PyTuple parts = (PyTuple) pair;
            if (parts.size() < 2) {
                throw Slots.typeError(
                        "%s.split must return a 2-tuple, got tuple of size %d",
                        group.exceptionType().name(), parts.size());
            }
            return new PyObject[] {parts.at(1), parts.at(0)};
        }
        return new PyObject[] {error, PyNone.INSTANCE};
    }

    /** {@code _PyExc_CreateExceptionGroup} of a naked exception, with this frame as its only entry. */
    private static PyObject wrap(PyBaseException raised, Frame frame) {
        PyObject made =
                Slots.call(
                        ExceptionTypes.BASE_EXCEPTION_GROUP,
                        new PyObject[] {PyStr.of(""), PyTuple.of(raised)},
                        null);
        PyTraceback.logFrame(frame, (PyBaseException) made);
        return made;
    }

    /**
     * {@code _PyExc_PrepReraiseStar}: the one exception an {@code except*} chain raises once all of its
     * clauses have run.
     *
     * <p>{@code excs} is what the bodies left behind — an exception per clause, and {@code None} for a
     * clause whose body finished — and the question is which of those came <em>out</em> of a handler
     * rather than being raised <em>by</em> one. CPython answers it by comparing metadata: an exception
     * that still carries the traceback, cause, context and notes of the group it was split out of is the
     * same one travelling on, and is put back with {@link #projection} so that it keeps its place in the
     * tree. Everything else is new, and the two kinds are then gathered into one group with the
     * projection on the end of them.
     *
     * <p>A value that is not a group at all is the one case where at most one clause can have run,
     * because a naked exception is wrapped before it reaches a clause and so matches exactly one of them;
     * and an empty list is {@code None}, which is how a driver with nothing to re-raise ends the
     * statement without raising at all.
     */
    static PyObject prepareReraise(PyObject original, PyList left) {
        if (left.size() == 0) {
            return PyNone.INSTANCE;
        }
        if (!(original instanceof PyBaseExceptionGroup group)) {
            return left.getItems().get(0);
        }
        List<PyObject> raised = new ArrayList<>();
        List<PyObject> reraised = new ArrayList<>();
        for (PyObject each : left.getItems()) {
            if (each == PyNone.INSTANCE) {
                continue;
            }
            (sameMetadata(each, original) ? reraised : raised).add(each);
        }
        PyObject again = projection(group, reraised);
        if (raised.isEmpty()) {
            return again;
        }
        if (again != PyNone.INSTANCE) {
            raised.add(again);
        }
        PyObject[] handed = raised.toArray(new PyObject[0]);
        return handed.length > 1
                ? create(
                        ExceptionTypes.BASE_EXCEPTION_GROUP,
                        new PyObject[] {PyStr.of(""), PyList.of(handed)},
                        false)
                : handed[0];
    }

    /**
     * {@code is_same_exception_metadata}: the four fields CPython compares by address, and nothing else.
     *
     * <p>The message is not compared, so a clause that raises {@code ValueError("different")} out of a
     * handler is a new exception rather than the old one travelling on — while the same object re-raised
     * by hand is, and keeps the position in the group it came from. {@code __notes__} is read out of the
     * dictionary this runtime keeps notes in, which is the field's job in CPython's.
     */
    private static boolean sameMetadata(PyObject one, PyObject other) {
        if (!(one instanceof PyBaseException a) || !(other instanceof PyBaseException b)) {
            return false;
        }
        return a.notes() == b.notes()
                && a.traceback() == b.traceback()
                && a.getAttr("__cause__") == b.getAttr("__cause__")
                && a.context() == b.context();
    }

    /**
     * {@code exception_group_projection}: the part of {@code original} whose leaves are among
     * {@code kept}, which is how an exception re-raised out of an {@code except*} body keeps its place
     * in the group it came from.
     */
    static PyObject projection(PyBaseException original, List<PyObject> kept) {
        Set<PyObject> leaves = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PyObject each : kept) {
            collectLeaves(each, leaves);
        }
        return orNone(walk(original, leaves::contains, false).match);
    }

    /** {@code collect_exception_group_leaf_ids}: a group gives its leaves, a leaf gives itself. */
    private static void collectLeaves(PyObject value, Set<PyObject> leaves) {
        if (value == null || value instanceof PyNone) {
            return;
        }
        if (value instanceof PyBaseExceptionGroup group) {
            for (PyObject child : group.exceptions.getArray()) {
                collectLeaves(child, leaves);
            }
            return;
        }
        leaves.add(value);
    }
}
