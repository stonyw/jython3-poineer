package org.python.core;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Python's {@code bytearray}: a mutable run of octets.
 *
 * <p>Everything a {@code bytes} answers about its contents, a bytearray answers the same way — an index
 * is an {@code int}, a slice another run of the same type, iteration one {@code int} per octet, and
 * comparison with either type reads the octets. What differs is that the run can be written: an index
 * assignment replaces one octet, a slice assignment may change the length, and {@code +=} and {@code *=}
 * resize in place.
 *
 * <p>The run keeps a capacity apart from its length, the way CPython's {@code ob_alloc} does, because the
 * growth it reports through {@code __sizeof__} is not the length: a fresh {@code bytearray(3)} holds three
 * octets in four, and appending to a full one grows it by at least an eighth. {@link #resize} is that
 * arithmetic and the only place the capacity moves.
 */
public final class PyByteArray extends PyObject {

    /** The exclusive end of the octet range {@code 0..255}, past {@link BigInteger}'s own byte. */
    private static final BigInteger OCTETS = BigInteger.valueOf(256);

    /** The octets in the first {@code value.length} slots; a capacity beyond that is not visible. */
    private byte[] value;

    /** The capacity of the allocation behind {@link #value}, which is what {@code __sizeof__} reports. */
    private int allocated;

    private PyByteArray(byte[] value, int allocated) {
        super(PyType.BYTEARRAY);
        this.value = value;
        this.allocated = allocated;
    }

    /** The empty bytearray, whose allocation is nothing at all rather than one spare octet. */
    static PyByteArray empty() {
        return new PyByteArray(new byte[0], 0);
    }

    /** A bytearray holding a copy of {@code content}, allocated one octet wider than it needs. */
    static PyByteArray of(byte[] content) {
        byte[] copy = content.clone();
        return new PyByteArray(copy, copy.length == 0 ? 0 : copy.length + 1);
    }

    /** Without copying: the caller hands over a run nothing else holds. */
    static PyByteArray wrap(byte[] content) {
        return new PyByteArray(content, content.length == 0 ? 0 : content.length + 1);
    }

    /** The live run, not a copy: the callers that read it promise not to write through it. */
    byte[] raw() {
        return value;
    }

    int size() {
        return value.length;
    }

    /** One octet, without copying the run: what {@code b[i]} and a walk read. */
    int byteAt(int index) {
        return value[index];
    }

    int allocated() {
        return allocated;
    }

    // --- allocation -------------------------------------------------------------------

    /**
     * Makes room for {@code requested} octets, keeping at least the old ones.
     *
     * <p>The capacity arithmetic is CPython's: a request that fits with one octet to spare either shrinks
     * the allocation to the request plus one (when it is below half the old capacity) or leaves it alone;
     * a request that still fits within an eighth of slack grows by that eighth plus a small constant; and
     * anything wider is allocated exactly, plus one. The log is what makes {@code __sizeof__} after a
     * sequence of appends reproducible rather than incidental.
     */
    private void resize(int requested) {
        int size = value.length;
        if (requested == size) {
            return;
        }
        int capacity = allocated;
        if ((long) requested + 1 <= capacity) {
            if (requested < capacity / 2) {
                capacity = requested + 1;
            }
        } else if (requested <= capacity + (capacity >> 3)) {
            capacity = requested + (requested >> 3) + (requested < 9 ? 3 : 6);
        } else {
            capacity = requested + 1;
        }
        byte[] next = new byte[requested];
        System.arraycopy(value, 0, next, 0, Math.min(size, requested));
        value = next;
        allocated = capacity;
    }

    /** Empties the run, leaving it as a fresh {@code bytearray()} is unless it already held something. */
    void clear() {
        resize(0);
    }

    /** Replaces the contents with a copy of {@code content}, after a {@link #clear}. */
    void set(byte[] content) {
        resize(content.length);
        System.arraycopy(content, 0, value, 0, content.length);
    }

    /** Replaces the contents with {@code count} zero octets. */
    void zeroed(int count) {
        resize(count);
        Arrays.fill(value, (byte) 0);
    }

    /** Appends one octet, growing the allocation the way an iterative constructor does. */
    void appendOctet(byte octet) {
        int size = value.length;
        resize(size + 1);
        value[size] = octet;
    }

    /** One item of a construction or an extension, refused in the mutable type's own singular words. */
    static byte octetOf(PyObject item) {
        BigInteger number = Numbers.asIndex(item);
        if (number.signum() < 0 || number.compareTo(OCTETS) >= 0) {
            throw Slots.valueError("byte must be in range(0, 256)");
        }
        return number.byteValue();
    }

    // --- sequence protocol -------------------------------------------------------------

    @Override
    public PyObject length() {
        return PyInt.of(value.length);
    }

    @Override
    public PyObject getItem(PyObject key) {
        if (Slices.isSlice(key)) {
            Slices.Range range = Slices.of((PySlice) key, value.length);
            byte[] out = new byte[range.count()];
            for (int i = 0; i < out.length; i++) {
                out[i] = value[range.at(i)];
            }
            return PyByteArray.wrap(out);
        }
        return PyInt.of(
                value[Slices.index(key, value.length, "bytearray", "bytearray index out of range")]
                        & 0xff);
    }

    @Override
    public PyObject setItem(PyObject key, PyObject item) {
        if (Slices.isSlice(key)) {
            setSlice((PySlice) key, item);
            return PyNone.INSTANCE;
        }
        int at = Slices.index(key, value.length, "bytearray", "bytearray index out of range");
        value[at] = octetOf(item);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject delItem(PyObject key) {
        if (Slices.isSlice(key)) {
            deleteSlice((PySlice) key);
            return PyNone.INSTANCE;
        }
        int at = Slices.index(key, value.length, "bytearray", "bytearray index out of range");
        int size = value.length;
        System.arraycopy(value, at + 1, value, at, size - at - 1);
        resize(size - 1);
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject contains(PyObject item) {
        return PyBytes.containsOctets(value, item);
    }

    @Override
    public PyObject iterator() {
        return SequenceIterators.of(this);
    }

    @Override
    public PyObject getAttr(String name) {
        return Members.find(this, name);
    }

    // --- methods -----------------------------------------------------------------------

    /**
     * What a bytearray object costs before its octets, which is what {@code __sizeof__} adds the
     * allocation to. CPython's own header for this type is 56 octets on this build.
     */
    private static final int OVERHEAD = 56;

    private static final Members METHODS = Members.of(PyType.BYTEARRAY)
            .addNoArgument("__alloc__", self -> PyInt.of(((PyByteArray) self).allocated))
            .addNoArgument(
                    "__sizeof__", self -> PyInt.of(OVERHEAD + ((PyByteArray) self).allocated))
            .addNoArgument("copy", self -> PyByteArray.of(((PyByteArray) self).value))
            .addNoArgument("clear", self -> {
                ((PyByteArray) self).clear();
                return PyNone.INSTANCE;
            })
            .addNoArgument("reverse", self -> ((PyByteArray) self).reversed())
            .addOne("append", (self, item) -> ((PyByteArray) self).appended(item))
            .addOne("extend", (self, source) -> ((PyByteArray) self).extended(source))
            .addOne("remove", (self, item) -> ((PyByteArray) self).removed(item))
            .addOne("resize", (self, size) -> ((PyByteArray) self).resized(size))
            .add("insert", (self, args, kwNames) -> {
                Arguments.noKeywords("bytearray.insert()", kwNames);
                Arguments.exactly("insert", args, kwNames, 2);
                return ((PyByteArray) self).inserted(args[0], args[1]);
            })
            .add("pop", (self, args, kwNames) -> {
                Arguments.noKeywords("bytearray.pop()", kwNames);
                Arguments.atMost("pop", args, kwNames, 1);
                return ((PyByteArray) self).popped(args.length == 0 ? null : args[0]);
            })
            .document(
                    "__alloc__",
                    "__alloc__($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "B.__alloc__() -> int\n"
                            + "\n"
                            + "Return the number of bytes actually allocated.")
            .document(
                    "__sizeof__",
                    "__sizeof__($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Returns the size of the bytearray object in memory, in bytes.")
            .document(
                    "copy",
                    "copy($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Return a copy of B.")
            .document(
                    "clear",
                    "clear($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Remove all items from the bytearray.")
            .document(
                    "reverse",
                    "reverse($self, /)\n"
                            + "--\n"
                            + "\n"
                            + "Reverse the order of the values in B in place.")
            .document(
                    "append",
                    "append($self, item, /)\n"
                            + "--\n"
                            + "\n"
                            + "Append a single item to the end of the bytearray.\n"
                            + "\n"
                            + "  item\n"
                            + "    The item to be appended.")
            .document(
                    "extend",
                    "extend($self, iterable_of_ints, /)\n"
                            + "--\n"
                            + "\n"
                            + "Append all the items from the iterator or sequence to the end of the bytearray.\n"
                            + "\n"
                            + "  iterable_of_ints\n"
                            + "    The iterable of items to append.")
            .document(
                    "insert",
                    "insert($self, index, item, /)\n"
                            + "--\n"
                            + "\n"
                            + "Insert a single item into the bytearray before the given index.\n"
                            + "\n"
                            + "  index\n"
                            + "    The index where the value is to be inserted.\n"
                            + "  item\n"
                            + "    The item to be inserted.")
            .document(
                    "pop",
                    "pop($self, index=-1, /)\n"
                            + "--\n"
                            + "\n"
                            + "Remove and return a single item from B.\n"
                            + "\n"
                            + "  index\n"
                            + "    The index from where to remove the item.\n"
                            + "    -1 (the default value) means remove the last item.\n"
                            + "\n"
                            + "If no index argument is given, will pop the last item.")
            .document(
                    "remove",
                    "remove($self, value, /)\n"
                            + "--\n"
                            + "\n"
                            + "Remove the first occurrence of a value in the bytearray.\n"
                            + "\n"
                            + "  value\n"
                            + "    The value to remove.")
            .document(
                    "resize",
                    "resize($self, size, /)\n"
                            + "--\n"
                            + "\n"
                            + "Resize the internal buffer of bytearray to len.\n"
                            + "\n"
                            + "  size\n"
                            + "    New size to resize to.");

    /** {@code append(item)}: one octet, in the words every other byte write uses. */
    private PyObject appended(PyObject item) {
        appendOctet(octetOf(item));
        return PyNone.INSTANCE;
    }

    /**
     * {@code extend(iterable_of_ints)}, which collects everything before it writes anything.
     *
     * <p>A bytes is appended as it stands and a bytearray is copied first, because the two runs may be
     * the same one: {@code b.extend(b)} doubles the value rather than reading the slots it is growing.
     * Every other iterable is walked into a buffer of its own, so an item out of range leaves the
     * receiver exactly as it was; a str is refused before the walk, because its characters are not
     * integers.
     */
    private PyObject extended(PyObject source) {
        byte[] tail;
        if (source instanceof PyBytes bytes) {
            tail = bytes.getValue();
        } else if (source instanceof PyByteArray bytearray) {
            tail = bytearray.value.clone();
        } else {
            if (source instanceof PyStr) {
                throw Slots.typeError(
                        "expected iterable of integers; got: '%s'", source.getType().filedName());
            }
            PyIterator iterator;
            try {
                iterator = Slots.iterator(source);
            } catch (PyRaise uniterable) {
                if (!uniterable.value().exceptionType().matches(ExceptionTypes.TYPE_ERROR)) {
                    throw uniterable;
                }
                throw Slots.typeError(
                        "can't extend bytearray with %s", source.getType().filedName());
            }
            byte[] grown = new byte[0];
            int size = 0;
            for (PyObject item = iterator.nextOrNull(); item != null; item = iterator.nextOrNull()) {
                byte octet = octetOf(item);
                if (size == grown.length) {
                    grown = Arrays.copyOf(grown, size == 0 ? 8 : size * 2);
                }
                grown[size++] = octet;
            }
            tail = Arrays.copyOf(grown, size);
        }
        int size = value.length;
        resize(size + tail.length);
        System.arraycopy(tail, 0, value, size, tail.length);
        return PyNone.INSTANCE;
    }

    /** {@code insert(index, item)}: the index is clamped to the ends, then one octet is made room for. */
    private PyObject inserted(PyObject index, PyObject item) {
        long at = Numbers.size(index);
        int size = value.length;
        if (at < 0) {
            at += size;
            if (at < 0) {
                at = 0;
            }
        }
        if (at > size) {
            at = size;
        }
        byte octet = octetOf(item);
        resize(size + 1);
        System.arraycopy(value, (int) at, value, (int) at + 1, size - (int) at);
        value[(int) at] = octet;
        return PyNone.INSTANCE;
    }

    /** {@code pop(index=-1)}: the index is read before the run is asked whether it holds anything. */
    private PyObject popped(PyObject index) {
        long at = index == null ? -1 : Numbers.size(index);
        int size = value.length;
        if (size == 0) {
            throw Slots.indexError("pop from empty bytearray");
        }
        if (at < 0) {
            at += size;
        }
        if (at < 0 || at >= size) {
            throw Slots.indexError("pop index out of range");
        }
        byte octet = value[(int) at];
        System.arraycopy(value, (int) at + 1, value, (int) at, size - (int) at - 1);
        resize(size - 1);
        return PyInt.of(octet & 0xff);
    }

    /** {@code remove(value)}: the first octet equal to the argument, or the refusal that names nothing. */
    private PyObject removed(PyObject item) {
        byte octet = octetOf(item);
        int size = value.length;
        for (int at = 0; at < size; at++) {
            if (value[at] == octet) {
                System.arraycopy(value, at + 1, value, at, size - at - 1);
                resize(size - 1);
                return PyNone.INSTANCE;
            }
        }
        throw Slots.valueError("value not found in bytearray");
    }

    /** {@code resize(size)}: a new length, filled with zeroes when it grows and cut when it shrinks. */
    private PyObject resized(PyObject size) {
        long requested = Numbers.size(size);
        if (requested < 0) {
            throw Slots.valueError("Can only resize to positive sizes, got %d", requested);
        }
        resize(Numbers.total(requested));
        return PyNone.INSTANCE;
    }

    /** {@code reverse()}: the octets in the opposite order, with the allocation left alone. */
    private PyObject reversed() {
        for (int left = 0, right = value.length - 1; left < right; left++, right--) {
            byte octet = value[left];
            value[left] = value[right];
            value[right] = octet;
        }
        return PyNone.INSTANCE;
    }

    @Override
    public PyObject binaryOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.ADD) {
            if (!(other instanceof PyBytes || other instanceof PyByteArray)) {
                throw Slots.typeError(
                        "can't concat %s to %s", other.getType().name(), getType().name());
            }
            return PyByteArray.of(PyBytes.concat(value, PyBytes.octets(other)));
        }
        if (op == BinaryOp.MUL) {
            return PyByteArray.of(repeated(other));
        }
        return PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject reflectedOp(BinaryOp op, PyObject other) {
        return op == BinaryOp.MUL ? binaryOp(op, other) : PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject inplaceOp(BinaryOp op, PyObject other) {
        if (op == BinaryOp.ADD) {
            if (!(other instanceof PyBytes || other instanceof PyByteArray)) {
                throw Slots.typeError(
                        "can't concat %s to %s", other.getType().name(), getType().name());
            }
            // `b += b` reads the receiver as its own source, and CPython's export count refuses the
            // resize that would follow. An empty receiver needs no room, so that call still succeeds.
            if (other == this && value.length > 0) {
                throw Slots.bufferError("Existing exports of data: object cannot be re-sized");
            }
            byte[] tail = PyBytes.octets(other).clone();
            int size = value.length;
            resize(size + tail.length);
            System.arraycopy(tail, 0, value, size, tail.length);
            return this;
        }
        if (op == BinaryOp.MUL) {
            long count = Sequences.multiplier(other);
            int total = Sequences.scaled(value.length, count, null);
            if (total == 0) {
                resize(0);
                return this;
            }
            byte[] original = value.clone();
            resize(total);
            for (int at = 0; at < total; ) {
                for (byte b : original) {
                    value[at++] = b;
                }
            }
            return this;
        }
        return PyNotImplemented.INSTANCE;
    }

    @Override
    public PyObject richCompare(CompareOp cmp, PyObject other) {
        return other instanceof PyBytes || other instanceof PyByteArray
                ? PyObject.ordered(cmp, Arrays.compare(value, PyBytes.octets(other)))
                : PyNotImplemented.INSTANCE;
    }

    @Override
    public boolean eq(PyObject other) {
        return (other instanceof PyBytes || other instanceof PyByteArray)
                && Arrays.equals(value, PyBytes.octets(other));
    }

    @Override
    public String repr() {
        return "bytearray(" + PyRepr.quoteByteArray(value) + ")";
    }

    // --- writing slices ----------------------------------------------------------------

    private static byte[] copied(PyObject source) {
        if (source instanceof PyBytes bytes) {
            return bytes.getValue();
        }
        if (source instanceof PyByteArray bytearray) {
            return bytearray.raw().clone();
        }
        return null;
    }

    /**
     * The octets a slice assignment writes, or the refusal its source earns.
     *
     * <p>A bytes or a bytearray is read directly. Anything else is either refused by name — a number or a
     * str is not the iterable the slot wants — or walked as an iterable, whose items are octets in the
     * same words every other write uses.
     */
    private static byte[] assignedOctets(PyObject source) {
        byte[] direct = copied(source);
        if (direct != null) {
            return direct;
        }
        if (Numbers.isNumber(source) || source instanceof PyStr) {
            throw Slots.typeError(
                    "can assign only bytes, buffers, or iterables of ints in range(0, 256)");
        }
        PyIterator iterator;
        try {
            iterator = Slots.iterator(source);
        } catch (PyRaise uniterable) {
            if (!uniterable.value().exceptionType().matches(ExceptionTypes.TYPE_ERROR)) {
                throw uniterable;
            }
            throw Slots.typeError(
                    "cannot convert '%s' object to bytearray", source.getType().filedName());
        }
        byte[] grown = new byte[0];
        int size = 0;
        for (PyObject item = iterator.nextOrNull(); item != null; item = iterator.nextOrNull()) {
            byte octet = octetOf(item);
            if (size == grown.length) {
                grown = Arrays.copyOf(grown, size == 0 ? 8 : size * 2);
            }
            grown[size++] = octet;
        }
        return Arrays.copyOf(grown, size);
    }

    private void setSlice(PySlice slice, PyObject source) {
        Slices.Range range = Slices.of(slice, value.length);
        byte[] content = assignedOctets(source);
        int size = value.length;
        if (range.step() == 1) {
            int start = range.start();
            int stop = range.stop();
            byte[] out = new byte[size - (stop - start) + content.length];
            System.arraycopy(value, 0, out, 0, start);
            System.arraycopy(content, 0, out, start, content.length);
            System.arraycopy(value, stop, out, start + content.length, size - stop);
            resize(out.length);
            System.arraycopy(out, 0, value, 0, out.length);
            return;
        }
        if (content.length != range.count()) {
            throw Slots.valueError(
                    "attempt to assign bytes of size %d to extended slice of size %d",
                    content.length, range.count());
        }
        for (int i = 0; i < content.length; i++) {
            value[range.at(i)] = content[i];
        }
    }

    private void deleteSlice(PySlice slice) {
        Slices.Range range = Slices.of(slice, value.length);
        int count = range.count();
        int size = value.length;
        if (count == 0) {
            return;
        }
        boolean[] dropped = new boolean[size];
        for (int i = 0; i < count; i++) {
            dropped[range.at(i)] = true;
        }
        int write = 0;
        for (int read = 0; read < size; read++) {
            if (!dropped[read]) {
                value[write++] = value[read];
            }
        }
        resize(size - count);
    }

    /** {@code bytearray * n}, which fails as a memory request and not as an oversized repetition. */
    private byte[] repeated(PyObject times) {
        int total = Sequences.scaled(value.length, Sequences.multiplier(times), null);
        if (total == 0) {
            return new byte[0];
        }
        byte[] out = new byte[total];
        for (int at = 0; at < total; ) {
            for (byte b : value) {
                out[at++] = b;
            }
        }
        return out;
    }
}
