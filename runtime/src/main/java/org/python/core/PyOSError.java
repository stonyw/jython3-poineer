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
 * <p>Two rules of CPython's are not modelled. Arg parsing is done in {@code __new__} here and never
 * deferred to {@code __init__}, so a class that declares an {@code __init__} of its own keeps the fields
 * the one call filled ({@code oserror_use_init} is the switch that empties them there). And {@code
 * PyNumber_Check} and {@code PyLong_Check} are answered for the four numeric builtins, so a value with
 * {@code __index__} or {@code __add__} of its own is not the count or the number CPython would read.
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
        PyObject number = null;
        PyObject text = null;
        PyObject firstName = null;
        PyObject secondName = null;
        // The fourth of the five is winerror, which is parsed everywhere and stored only on Windows.
        if (given.length >= 2 && given.length <= 5) {
            number = given[0];
            text = given[1];
            firstName = given.length > 2 ? given[2] : null;
            secondName = given.length > 4 ? given[4] : null;
        }
        PyType chosen = kind;
        BigInteger asked = number == null ? null : Numbers.asInteger(number);
        if (kind == ExceptionTypes.OS_ERROR && asked != null) {
            // PyDict_GetItemWithError: the field as a dictionary key, so a bool is read as its number.
            PyType mapped = ERRNO_MAP.get(asked);
            if (mapped != null) {
                chosen = mapped;
            }
        }
        PyOSError made = new PyOSError(chosen, given);
        if (firstName != null && firstName != PyNone.INSTANCE) {
            if (chosen == ExceptionTypes.BLOCKING_IO_ERROR && counted(firstName)) {
                made.written = Numbers.count(firstName);
            } else {
                made.firstName = firstName;
                if (secondName != null && secondName != PyNone.INSTANCE) {
                    made.secondName = secondName;
                }
                made.setArguments(new PyObject[] {given[0], given[1]});
            }
        }
        made.number = number;
        made.text = text;
        return made;
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
     * {@code e.errno} and its three siblings, then {@code e.characters_written}.
     *
     * <p>Four of the five answer {@code None} for a field that was never filled; the getset has no such
     * answer — it refuses by name, which is the only message of CPython's here that is a bare attribute.
     *
     * <p>The field is only what the lookup order reaches when nothing nearer does: {@code _PyType_Lookup}
     * walks the order once, so a class under {@code OSError} that files a value or a property under one of
     * these five names is asked for it first, and {@code class Filed(OSError): errno = 42} reads {@code 42}
     * where {@code Filed(1, "m")} still displays {@code [Errno 1] m}.
     */
    @Override
    public PyObject getAttr(String name) {
        if (!holds(name) || claimed(name)) {
            return super.getAttr(name);
        }
        return switch (name) {
            case "errno" -> orNone(number);
            case "strerror" -> orNone(text);
            case "filename" -> orNone(firstName);
            case "filename2" -> orNone(secondName);
            default -> read();
        };
    }

    private PyObject read() {
        if (written == UNSET) {
            throw Slots.attributeError("characters_written");
        }
        return PyInt.of(written);
    }

    /** Whether {@code name} is one of the five the class keeps beside its arguments. */
    private static boolean holds(String name) {
        return switch (name) {
            case "errno", "strerror", "filename", "filename2", "characters_written" -> true;
            default -> false;
        };
    }

    /**
     * Whether the lookup order names {@code name} before it reaches the field, which is how a member
     * descriptor is found: CPython walks the order once and only asks the descriptor it lands on, so
     * whatever a written class holds under the same name — a plain number, a property, a slot — owns the
     * read, the write and the delete alike.
     */
    private boolean claimed(String name) {
        return Specials.member(exceptionType(), name) != null;
    }

    /**
     * {@code e.errno = value} for the four members — any value, and {@code None} is one of them, which is
     * what puts {@code None} into the text rather than taking the field out — and {@code
     * e.characters_written = value}, which converts.
     *
     * <p>A class that declares either hook is asked before the field is, exactly as {@code
     * PyObject_GenericSetAttr} asks it of every other name, and a class that files its own value under the
     * name takes the write into its dictionary rather than into the field.
     */
    @Override
    public PyObject setAttr(String name, PyObject value) {
        if (!holds(name) || claimed(name) || Specials.lookUp(exceptionType(), "__setattr__") != null) {
            return super.setAttr(name, value);
        }
        switch (name) {
            case "errno" -> {
                number = value;
                return PyNone.INSTANCE;
            }
            case "strerror" -> {
                text = value;
                return PyNone.INSTANCE;
            }
            case "filename" -> {
                firstName = value;
                return PyNone.INSTANCE;
            }
            case "filename2" -> {
                secondName = value;
                return PyNone.INSTANCE;
            }
            case "characters_written" -> {
                // Only a delete is given the null pointer; None here is the object, and is converted.
                written = Numbers.count(value);
                return PyNone.INSTANCE;
            }
            default -> {
            }
        }
        return super.setAttr(name, value);
    }

    /**
     * {@code del} for the five: the members take their field away and read back as {@code None}, and the
     * getset refuses when there was nothing to take.
     *
     * <p>Only where the lookup order actually lands on the field. A class that files its own value under
     * the name is deleted out of its dictionary, and so complains the ordinary way when the dictionary has
     * nothing to take.
     */
    @Override
    public PyObject delAttr(String name) {
        if (!holds(name) || claimed(name) || Specials.lookUp(exceptionType(), "__delattr__") != null) {
            return super.delAttr(name);
        }
        switch (name) {
            case "errno" -> {
                number = null;
                return PyNone.INSTANCE;
            }
            case "strerror" -> {
                text = null;
                return PyNone.INSTANCE;
            }
            case "filename" -> {
                firstName = null;
                return PyNone.INSTANCE;
            }
            case "filename2" -> {
                secondName = null;
                return PyNone.INSTANCE;
            }
            case "characters_written" -> {
                if (written == UNSET) {
                    throw Slots.attributeError("characters_written");
                }
                written = UNSET;
                return PyNone.INSTANCE;
            }
            default -> {
            }
        }
        return super.delAttr(name);
    }
}
