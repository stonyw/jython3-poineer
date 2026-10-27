package org.python.core;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An {@code OSError}: the one exception whose value carries fields beside its arguments.
 *
 * <p>CPython stores five of them — {@code myerrno}, {@code strerror}, {@code filename}, {@code
 * filename2} and the C {@code ssize_t} behind {@code characters_written} — and publishes four as {@code
 * PyMemberDef}s of type {@code _Py_T_OBJECT} plus one getset. Two of that is observable and easy to lose.
 * A {@code T_OBJECT} field that was never filled reads as {@code None} but is not {@code None}, and the
 * difference shows in {@code str(e)}: the format CPython picks tests the field for being there, so
 * {@code OSError(1, "a", "f", 5, None)} has no second file in its text while {@code e.filename2 = None}
 * writes the object and puts {@code -> None} back. Deleting one writes the null pointer, which is why
 * {@code del e.errno} and {@code e.errno = None} read the same and display differently.
 *
 * <p>The arguments are a second decision. Only a call of two to five arguments is read as this signature,
 * so {@code OSError("only")} has no error number at all and reads {@code errno} as {@code None} while
 * {@code OSError(1, "a")} has one; and a third argument that is not {@code None} is taken as a file name
 * and cut out of the arguments, which stay the first two. Six arguments therefore keep all six. The
 * exception is {@code BlockingIOError}, whose third argument is a count of characters when it is a
 * number, and then nothing is cut and nothing is filed as a file — which is what makes {@code
 * BlockingIOError(11, "m", 3).args} three items long and {@code .filename} {@code None}.
 *
 * <p>Which class the value gets is not the class that was called. {@code OSError_new} looks the number up
 * in the interpreter's {@code errnomap} and builds the narrower class when it finds one, so {@code
 * OSError(2, "m")} is a {@code FileNotFoundError} — but only for that class exactly, never for a subclass
 * of it, and only when the first argument answers {@code PyLong_Check}, which is why {@code
 * OSError(True, "m")} becomes a {@code PermissionError} over the dictionary's equal key of {@code 1} and
 * {@code OSError("1", "m")} stays an {@code OSError} with a text in its number field.
 *
 * <p>One rule of CPython's is not modelled. {@code PyNumber_Check} and {@code PyLong_Check} are answered
 * for the four numeric builtins, so a value with {@code __index__} or {@code __add__} of its own is not the
 * count or the number CPython would read.
 */
public final class PyOSError extends PyBaseException {

    /**
     * {@code _PyExc_InitState}'s {@code errnomap}: the error number and the class the base is asked to
     * become.
     *
     * <p>The numbers are those of the platform this table was measured on, which is what CPython's {@code
     * ADD_ERRNO} lines expand to there: {@code EAGAIN} and {@code EWOULDBLOCK} are one number here, as
     * they are everywhere, and {@code ENOTCAPABLE} and {@code ESHUTDOWN} are in the map because the header
     * defines them. Another platform reads the same two numbers differently — {@code OSError(11, "m")} is a
     * {@code BlockingIOError} where {@code EAGAIN} is 11 and an ordinary {@code OSError} here — so this is
     * one table per platform rather than a rule about errors, and the names beside each line are the ones
     * the C header uses.
     */
    private static final Map<BigInteger, PyType> ERRNO_MAP = errnoMap();

    private static Map<BigInteger, PyType> errnoMap() {
        Map<BigInteger, PyType> map = new LinkedHashMap<>();
        add(map, ExceptionTypes.BLOCKING_IO_ERROR, 35, 36, 37); // EAGAIN/EWOULDBLOCK, EINPROGRESS, EALREADY
        add(map, ExceptionTypes.BROKEN_PIPE_ERROR, 32, 58); // EPIPE, ESHUTDOWN
        add(map, ExceptionTypes.CHILD_PROCESS_ERROR, 10); // ECHILD
        add(map, ExceptionTypes.CONNECTION_ABORTED_ERROR, 53); // ECONNABORTED
        add(map, ExceptionTypes.CONNECTION_REFUSED_ERROR, 61); // ECONNREFUSED
        add(map, ExceptionTypes.CONNECTION_RESET_ERROR, 54); // ECONNRESET
        add(map, ExceptionTypes.FILE_EXISTS_ERROR, 17); // EEXIST
        add(map, ExceptionTypes.FILE_NOT_FOUND_ERROR, 2); // ENOENT
        add(map, ExceptionTypes.INTERRUPTED_ERROR, 4); // EINTR
        add(map, ExceptionTypes.IS_A_DIRECTORY_ERROR, 21); // EISDIR
        add(map, ExceptionTypes.NOT_A_DIRECTORY_ERROR, 20); // ENOTDIR
        add(map, ExceptionTypes.PERMISSION_ERROR, 1, 13, 107); // EPERM, EACCES, ENOTCAPABLE
        add(map, ExceptionTypes.PROCESS_LOOKUP_ERROR, 3); // ESRCH
        add(map, ExceptionTypes.TIMEOUT_ERROR, 60); // ETIMEDOUT
        return map;
    }

    private static void add(Map<BigInteger, PyType> map, PyType type, int... numbers) {
        for (int number : numbers) {
            map.put(BigInteger.valueOf(number), type);
        }
    }

    /** {@code characters_written} is a C word, and {@code -1} in it means the field was never filled. */
    private static final long UNSET = -1;

    /** The four {@code _Py_T_OBJECT} members. Null is CPython's null field, read as {@code None}. */
    private PyObject number;

    private PyObject text;
    private PyObject firstName;
    private PyObject secondName;

    /** The {@code written} word, which the {@code BlockingIOError} count and the getset both fill. */
    private long written = UNSET;

    private PyOSError(PyType kind, PyObject[] values) {
        super(kind, values);
    }

    /**
     * {@code OSError_new} over the arguments as the call gave them.
     *
     * <p>{@code oserror_parse_args} then {@code oserror_init}, in that order, because the number is chosen
     * from the parsed first argument before the value exists and the arguments are cut after the file name
     * was taken out of them.
     */
    static PyBaseException create(PyType kind, PyObject[] given) {
        // `oserror_use_init` guards the whole of `OSError_new`'s own work — the unpacking, the error number
        // looked up in the map and all — so a class that declares an `__init__` and no `__new__` is made
        // empty here and left for that initialiser to fill, and is not promoted whatever it was called with.
        if (defersToInit(kind)) {
            return new PyOSError(kind, new PyObject[0]);
        }
        // The unpacking window is what decides whether there is an error number to look up at all: a
        // one-argument call parses to nothing, and `PyLong_Check(NULL)` never reaches the map.
        BigInteger asked =
                given.length >= 2 && given.length <= 5 ? Numbers.asInteger(given[0]) : null;
        PyType chosen = kind;
        if (kind == ExceptionTypes.OS_ERROR && asked != null) {
            // PyDict_GetItemWithError: the field as a dictionary key, so a bool is read as its number.
            PyType mapped = ERRNO_MAP.get(asked);
            if (mapped != null) {
                chosen = mapped;
            }
        }
        PyOSError made = new PyOSError(chosen, given);
        made.initialize(given);
        return made;
    }

    /**
     * {@code oserror_parse_args} and {@code oserror_init} together, over the arguments of the call.
     *
     * <p>Both halves of CPython run this way: {@code OSError_new} for a class that does not parse for
     * itself, and {@code OSError_init} for one that defers — {@link #defersToInit} is the switch between
     * them. Neither promotes the value to a narrower class, which is {@code OSError_new}'s alone to do,
     * and neither fills {@code written} unless the class is exactly {@code BlockingIOError}.
     *
     * <p>The fourth of the five is {@code winerror}, which is parsed everywhere and stored only on Windows;
     * on this platform it is read and thrown away, and the third and fifth are kept only when they are
     * there <em>and</em> not {@code None} — which is what lets {@code OSError(1, "a", "f", 5, None)} display
     * one file and {@code e.filename2 = None} display two.
     *
     * <p>The arguments are taken over whatever else happens, and cut to the first two only where a file
     * name was taken out of them — so {@code super().__init__()} of an {@code OSError} empties {@code args}
     * as surely as it empties the number, and a {@code BlockingIOError} that was handed a count keeps all
     * three.
     */
    void initialize(PyObject[] given) {
        PyObject[] arguments = given;
        PyObject number = null;
        PyObject text = null;
        PyObject firstName = null;
        PyObject secondName = null;
        if (given.length >= 2 && given.length <= 5) {
            number = given[0];
            text = given[1];
            firstName = given.length > 2 ? given[2] : null;
            secondName = given.length > 4 ? given[4] : null;
        }
        if (firstName != null && firstName != PyNone.INSTANCE) {
            if (getType() == ExceptionTypes.BLOCKING_IO_ERROR && counted(firstName)) {
                written = Numbers.count(firstName);
            } else {
                this.firstName = firstName;
                if (secondName != null && secondName != PyNone.INSTANCE) {
                    this.secondName = secondName;
                }
                arguments = new PyObject[] {given[0], given[1]};
            }
        }
        setArguments(arguments);
        this.number = number;
        this.text = text;
    }

    /**
     * {@code oserror_use_init}: whether the class leaves this work to {@code __init__}.
     *
     * <p>The two slots CPython compares are {@code tp_init} and {@code tp_new}, and a heap type fills both
     * from its base unless the class body declares the name — so in this runtime the same question is
     * whether the order files an {@code __init__} of its own and no {@code __new__}. A class that declares
     * both parses in the creator it wrote, and one that declares neither is made whole by {@code
     * OSError_new} and then refuses to be initialised again.
     */
    static boolean defersToInit(PyType kind) {
        return Specials.lookUp(kind, "__init__") != null
                && Specials.lookUp(kind, "__new__") == null;
    }

    /** {@code PyNumber_Check} for the builtins this runtime has: a number, however it is spelled. */
    private static boolean counted(PyObject value) {
        return value instanceof PyInt
                || value instanceof PyBool
                || value instanceof PyFloat
                || value instanceof PyComplex;
    }

    /**
     * {@code OSError_str}: the error number and the text, with each file name the value was given.
     *
     * <p>Two format verbs and they are not interchangeable: {@code %S} reads the number and the text as
     * {@code str} says them, so a {@code str} error number appears bare, while {@code %R} quotes a file
     * name the way a repr does. Everything else falls to {@code BaseException_str} over the arguments,
     * which is what makes {@code OSError(1, "a")} and {@code OSError("1", "a")} read alike and differ from
     * the value whose number was deleted.
     */
    @Override
    public String str() {
        PyObject declared = displayed("__str__");
        if (declared != null) {
            return display(declared, "__str__");
        }
        return errorText();
    }

    /**
     * {@code OSError_str} itself, without the {@code __str__} question first.
     *
     * <p>The half the {@code wrapper_descriptor} this class files calls, and the reason {@code
     * BaseException.__str__(e)} of an {@code OSError} answers over the arguments while {@code
     * OSError.__str__(e)} answers over the fields: CPython hands a slot wrapper the function of the type
     * that filed it, not the one the value's own class would have run.
     */
    String errorText() {
        if (firstName != null) {
            if (secondName != null) {
                return "[Errno " + orNone(number).str() + "] " + orNone(text).str() + ": "
                        + firstName.repr() + " -> " + secondName.repr();
            }
            return "[Errno " + orNone(number).str() + "] " + orNone(text).str() + ": "
                    + firstName.repr();
        }
        if (number != null && text != null) {
            return "[Errno " + number.str() + "] " + text.str();
        }
        return described();
    }

    /** {@code OR_NONE}, the macro that lets a deleted field be displayed as {@code None}. */
    private static PyObject orNone(PyObject field) {
        return field == null ? PyNone.INSTANCE : field;
    }

    /**
     * {@code PyMember_GetOne} over one of the four {@code _Py_T_OBJECT} offsets.
     *
     * <p>An offset that was never filled reads as {@code None} — {@code OR_NONE}, the same macro the
     * display uses — which is what makes {@code del e.errno} and {@code OSError("only").errno} answer alike
     * while {@code str(e)} still tells them apart.
     *
     * <p>The field is only what the lookup order reaches when nothing nearer does: {@code _PyType_Lookup}
     * walks the order once, so a class under {@code OSError} that files a value or a property under one of
     * these names is asked for it first, and {@code class Filed(OSError): errno = 42} reads {@code 42} where
     * {@code Filed(1, "m")} still displays {@code [Errno 1] m}.
     */
    PyObject memberField(String name) {
        return orNone(
                switch (name) {
                    case "errno" -> number;
                    case "strerror" -> text;
                    case "filename" -> firstName;
                    default -> secondName;
                });
    }

    /**
     * {@code PyMember_SetOne} over the same four, with {@code null} standing for {@code del}.
     *
     * <p>Any value arrives, and {@code None} is one of them, which is what puts {@code None} into the text
     * rather than taking the field out; a delete writes the null pointer, which is the one thing the read
     * cannot tell apart from a field that was never filled.
     */
    void writeMemberField(String name, PyObject value) {
        switch (name) {
            case "errno" -> number = value;
            case "strerror" -> text = value;
            case "filename" -> firstName = value;
            default -> secondName = value;
        }
    }

    /**
     * {@code OSError_written_get}: the C word, or the name it refuses by.
     *
     * <p>The only bare {@code AttributeError} in this family, because the getter has no sentence of its own
     * to choose and no {@code OR_NONE} to fall back on — {@code written} is a {@code ssize_t}, and -1 in it
     * means the count was never given.
     */
    PyObject charactersWritten() {
        if (written == UNSET) {
            throw Slots.attributeError("characters_written");
        }
        return PyInt.of(written);
    }

    /** {@code OSError_written_set}: {@code PyNumber_AsSsize_t} over the value, and {@code del} as -1. */
    void writeCharactersWritten(PyObject value) {
        if (value == null) {
            if (written == UNSET) {
                throw Slots.attributeError("characters_written");
            }
            written = UNSET;
            return;
        }
        // Only a delete is handed the null pointer; None here is the object, and is converted.
        written = Numbers.count(value);
    }

    /**
     * {@code OSError_reduce}'s first half: the arguments, with the file names put back in.
     *
     * <p>{@code self->args} is only the first two real arguments when a file name was given, so the value
     * reports the call that would make it again. The test is of the {@code filename} <em>pointer</em>,
     * which is why a {@code filename} written as {@code None} still rebuilds the tuple — with a {@code
     * None} in it this time — and why a {@code filename2} alone rebuilds nothing. The five-place form
     * leaves the fourth as {@code None}, because that is the only way {@code OSError(*args)} can be handed
     * a second file name on a platform that stores no {@code winerror}.
     */
    @Override
    PyObject reducedArguments() {
        PyTuple kept = args();
        if (kept.size() == 2 && firstName != null) {
            if (secondName != null) {
                return PyTuple.of(
                        kept.at(0), kept.at(1), firstName, PyNone.INSTANCE, secondName);
            }
            return PyTuple.of(kept.at(0), kept.at(1), firstName);
        }
        return kept;
    }
}
