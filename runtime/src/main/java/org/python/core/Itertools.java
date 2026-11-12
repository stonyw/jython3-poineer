package org.python.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code itertools}: the cursors the reference builds in {@code itertoolsmodule.c}, over the same iterable
 * protocol every other cursor here uses.
 *
 * <p>Only the ones whose whole state is a few fields are here. {@code tee}, {@code groupby},
 * {@code combinations} and {@code combinations_with_replacement} are not: they are nothing but a different
 * walk of the same protocol, and the plan files them under the same "missing C module" heading as the rest
 * of the absent surface. {@code permutations} and {@code product} arrived with the M6-a verification
 * slice, because {@code tokenize} enumerates the string-prefix permutations at import.
 *
 * <p>Each type is filed under {@code itertools.name} so that {@code type(count(1)).__name__} and
 * {@code __module__} answer as in the reference. {@code count} and {@code repeat} print their state, as
 * theirs do; every other cursor prints the same {@code <itertools.name object at 0x...>} the reference
 * leaves to {@code object.__repr__}, which is why {@link Cursor} writes it once.
 */
final class Itertools {

    /** {@code chain.from_iterable}'s docstring, the one row {@code chain} has beyond the type itself. */
    private static final String FROM_ITERABLE_DOC =
            "chain.from_iterable(iterable) --> chain object\n"
                    + "\n"
                    + "Alternate chain() constructor taking a single iterable argument\n"
                    + "that evaluates lazily.";

    /** {@code chain}'s one method, which is what makes {@code chain.from_iterable} a class attribute. */
    private static final Members CHAIN_BOOK =
            Members.of(PyType.CHAIN)
                    .addClassNamed(
                            "from_iterable",
                            FROM_ITERABLE_DOC,
                            (type, args, kwNames) -> fromIterable(args, kwNames));

    private static final BigInteger ONE = BigInteger.ONE;

    private Itertools() {
    }

    /** Build the module, in the reference's own name order. */
    static PyModule newModule() {
        PyModule module = new PyModule("itertools");
        module.set("__doc__", PyStr.of("Functional tools for creating and using iterators."));
        PyRuntime.markBuiltIn(module, "itertools");
        module.set("count", PyType.COUNT);
        module.set("cycle", PyType.CYCLE);
        module.set("repeat", PyType.REPEAT);
        module.set("accumulate", PyType.ACCUMULATE);
        module.set("chain", PyType.CHAIN);
        module.set("compress", PyType.COMPRESS);
        module.set("dropwhile", PyType.DROPWHILE);
        module.set("filterfalse", PyType.FILTERFALSE);
        module.set("islice", PyType.ISLICE);
        module.set("starmap", PyType.STAR_MAP);
        module.set("takewhile", PyType.TAKE_WHILE);
        module.set("zip_longest", PyType.ZIP_LONGEST);
        module.set("batched", PyType.BATCHED);
        module.set("pairwise", PyType.PAIRWISE);
        module.set("permutations", PyType.PERMUTATIONS);
        module.set("product", PyType.PRODUCT);
        return module;
    }

    /**
     * The {@code object.__repr__} every cursor keeps: the type's filed name, in hex, at its identity.
     *
     * <p>{@link PyObject}'s own version prints the bare name and a decimal address, which is right for a
     * builtin's {@code zip} and wrong for anything the reference files in a module.
     */
    abstract static class Cursor extends PyIterator {

        Cursor(PyType type) {
            super(type);
        }

        @Override
        public String repr() {
            return "<" + getType().filedName() + " object at 0x"
                    + Long.toHexString(System.identityHashCode(this)) + ">";
        }
    }

    /** {@code chain(...)}: an outer cursor of iterables, and whichever iterable it is walking now. */
    static final class Chain extends Cursor {
        private final PyIterator sources;
        private PyIterator current;

        Chain(PyObject iterables) {
            super(PyType.CHAIN);
            this.sources = Slots.iterator(iterables);
        }

        @Override
        public PyObject nextOrNull() {
            while (true) {
                if (current != null) {
                    PyObject item = current.nextOrNull();
                    if (item != null) {
                        return item;
                    }
                    current = null;
                }
                PyObject source = sources.nextOrNull();
                if (source == null) {
                    return null;
                }
                current = Slots.iterator(source);
            }
        }
    }

    /**
     * {@code chain(...)}, the entries of all the iterables in turn.
     *
     * <p>The whole argument list is one tuple walked by the same cursor {@code chain.from_iterable} walks
     * one iterable with, so {@code chain()} is a chain over nothing and ends immediately.
     */
    static PyObject chaining(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("chain()", kwNames);
        return new Chain(PyTuple.of(args));
    }

    /** {@code chain.from_iterable(iterable)}: the outer cursor is the argument itself. */
    static PyObject fromIterable(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("from_iterable()", kwNames);
        Arguments.exactly("from_iterable", args, kwNames, 1);
        return new Chain(args[0]);
    }

    /** {@code count(start=0, step=1, /)}: consecutive values, integral until one argument is not. */
    static final class Count extends Cursor {
        private final boolean floating;
        private BigInteger integer;
        private final BigInteger integerStep;
        private double real;
        private final double realStep;
        private final PyObject start;
        private final PyObject step;

        Count(PyObject start, PyObject step) {
            super(PyType.COUNT);
            this.start = start;
            this.step = step;
            BigInteger from = Numbers.asInteger(start);
            BigInteger by = Numbers.asInteger(step);
            if (from != null && by != null) {
                floating = false;
                integer = from;
                integerStep = by;
                real = 0;
                realStep = 0;
            } else {
                floating = true;
                integer = BigInteger.ZERO;
                integerStep = BigInteger.ZERO;
                real = real(start);
                realStep = real(step);
            }
        }

        /**
         * A count's start or step, as the double the reference keeps once either is one.
         *
         * <p>{@code PyNumber_AsSsize_t} is not what reads these: {@code count} takes a number, and the two
         * refusals are "an integer is required" for a value with no number slots at all.
         */
        private static double real(PyObject value) {
            Double number = Numbers.floatOf(value);
            if (number == null) {
                throw Slots.typeError("a number is required");
            }
            return number;
        }

        @Override
        public PyObject nextOrNull() {
            if (floating) {
                double value = real;
                real += realStep;
                return PyFloat.of(value);
            }
            BigInteger value = integer;
            integer = integer.add(integerStep);
            return PyInt.of(value);
        }

        @Override
        public String repr() {
            boolean unit = floating
                    ? realStep == 1.0
                    : integerStep.equals(ONE);
            return "count(" + start.repr() + (unit ? "" : ", " + step.repr()) + ")";
        }
    }

    /** {@code count('start=', 'step=', /)}: the two arguments, both optional. */
    static PyObject counting(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("count()", kwNames);
        Arguments.atMostQualified("count", args, kwNames, 2);
        return new Count(
                args.length > 0 ? args[0] : PyInt.of(0),
                args.length > 1 ? args[1] : PyInt.of(1));
    }

    /** {@code repeat(object[, times])}: the same value, a counted number of times or forever. */
    static final class Repeat extends Cursor {
        private final PyObject value;
        private BigInteger remaining;
        private final PyObject times;

        Repeat(PyObject value, PyObject times) {
            super(PyType.REPEAT);
            this.value = value;
            this.times = times;
            this.remaining = times == null ? null : Numbers.asIndex(times);
        }

        @Override
        public PyObject nextOrNull() {
            if (remaining == null) {
                return value;
            }
            if (remaining.signum() <= 0) {
                return null;
            }
            remaining = remaining.subtract(ONE);
            return value;
        }

        @Override
        public String repr() {
            return "repeat(" + value.repr() + (times == null ? "" : ", " + times.repr()) + ")";
        }
    }

    /** {@code repeat(object, times=None)}: both parameter names are usable as keywords. */
    static PyObject repeating(PyObject[] args, String[] kwNames) {
        Arguments.atMostQualified("repeat", args, kwNames, 2);
        PyObject[] values = Arguments.fill(
                "repeat",
                args,
                kwNames,
                new String[] {"object", "times"},
                new PyObject[] {null, PyNone.INSTANCE});
        Arguments.required("repeat", new String[] {"object", "times"}, values, 0);
        return new Repeat(values[0], values[1] == PyNone.INSTANCE ? null : values[1]);
    }

    /** {@code cycle(iterable)}: the source saved as it is read, then replayed from the start forever. */
    static final class Cycle extends Cursor {
        private final PyIterator source;
        private final List<PyObject> saved = new ArrayList<>();
        private boolean savedOut;
        private int at;

        Cycle(PyObject iterable) {
            super(PyType.CYCLE);
            this.source = Slots.iterator(iterable);
        }

        @Override
        public PyObject nextOrNull() {
            if (!savedOut) {
                PyObject item = source.nextOrNull();
                if (item != null) {
                    saved.add(item);
                    return item;
                }
                savedOut = true;
                at = 0;
            }
            if (saved.isEmpty()) {
                return null;
            }
            PyObject item = saved.get(at);
            at = (at + 1) % saved.size();
            return item;
        }
    }

    /** {@code cycle(iterable, /)}: one positional argument, and no keyword. */
    static PyObject cycling(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("cycle()", kwNames);
        Arguments.exactly("cycle", args, kwNames, 1);
        return new Cycle(args[0]);
    }

    /** {@code islice(iterable, stop[, step])}: the indices already validated, walked as a slice does. */
    static final class Islice extends Cursor {
        private final PyIterator source;
        private final BigInteger stop;
        private final BigInteger step;
        private BigInteger index = BigInteger.ZERO;
        private BigInteger next = BigInteger.ZERO;

        Islice(PyObject iterable, BigInteger start, BigInteger stop, BigInteger step) {
            super(PyType.ISLICE);
            this.source = Slots.iterator(iterable);
            this.stop = stop;
            this.step = step;
            this.next = start;
        }

        @Override
        public PyObject nextOrNull() {
            if (stop != null && next.compareTo(stop) >= 0) {
                return null;
            }
            while (index.compareTo(next) < 0) {
                if (source.nextOrNull() == null) {
                    return null;
                }
                index = index.add(ONE);
            }
            PyObject item = source.nextOrNull();
            if (item == null) {
                return null;
            }
            index = index.add(ONE);
            next = next.add(step);
            return item;
        }
    }

    /**
     * {@code islice(iterable, stop)} or {@code islice(iterable, start, stop[, step])}, positionally only.
     *
     * <p>A {@code None} is the same as leaving the argument out; a non-integer is not a {@code TypeError}
     * about the value but a {@code ValueError} about the argument, in the four sentences the reference
     * writes, because that is what {@code islice} itself says after its own conversion fails.
     */
    static PyObject slicing(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("islice()", kwNames);
        Arguments.between("islice", args, kwNames, 2, 4);
        BigInteger start;
        BigInteger stop;
        BigInteger step = ONE;
        if (args.length == 2) {
            start = BigInteger.ZERO;
            stop = bound(args[1], "Stop argument for islice() must be None or an integer:"
                    + " 0 <= x <= sys.maxsize.", true);
        } else {
            start = bound(args[1], "Indices for islice() must be None or an integer:"
                    + " 0 <= x <= sys.maxsize.", false);
            stop = bound(args[2], "Stop argument for islice() must be None or an integer:"
                    + " 0 <= x <= sys.maxsize.", true);
            if (args.length > 3) {
                step = bound(args[3], "Step for islice() must be a positive integer or None.", false);
                if (step.signum() <= 0) {
                    throw Slots.valueError(
                            "Step for islice() must be a positive integer or None.");
                }
            }
        }
        return new Islice(args[0], start == null ? BigInteger.ZERO : start, stop, step);
    }

    /** One of islice's three counts: {@code None}, a non-negative integer, or the sentence naming it. */
    private static BigInteger bound(PyObject value, String refusal, boolean optional) {
        if (value == PyNone.INSTANCE) {
            return optional ? null : BigInteger.ZERO;
        }
        BigInteger number = Numbers.asInteger(value);
        if (number == null) {
            try {
                number = Numbers.asIndex(value);
            } catch (PyRaise notAnIndex) {
                throw Slots.valueError(refusal);
            }
        }
        if (number.signum() < 0) {
            throw Slots.valueError(refusal);
        }
        return number;
    }

    /** {@code accumulate(iterable, func=None, *, initial=None)}: running totals, added or folded. */
    static final class Accumulate extends Cursor {
        private final PyIterator source;
        private final PyObject function;
        private final boolean hasInitial;
        private PyObject total;
        private boolean started;

        Accumulate(PyObject iterable, PyObject function, PyObject initial, boolean hasInitial) {
            super(PyType.ACCUMULATE);
            this.source = Slots.iterator(iterable);
            this.function = function == PyNone.INSTANCE ? null : function;
            this.hasInitial = hasInitial;
            this.total = initial;
        }

        @Override
        public PyObject nextOrNull() {
            if (!started) {
                started = true;
                if (hasInitial) {
                    return total;
                }
                total = source.nextOrNull();
                return total;
            }
            PyObject item = source.nextOrNull();
            if (item == null) {
                return null;
            }
            total = function == null
                    ? Slots.binary(BinaryOp.ADD, total, item)
                    : Slots.call(function, new PyObject[] {total, item}, null);
            return total;
        }
    }

    /**
     * {@code accumulate(iterable, func=None, *, initial=None)}: the one cursor here with a keyword-only
     * parameter, which is why the names are read by hand rather than through {@link Arguments#fill}.
     */
    static PyObject accumulating(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional > 2) {
            throw Slots.typeError(
                    "accumulate() takes at most 2 positional arguments (%d given)", positional);
        }
        PyObject iterable = positional > 0 ? args[0] : null;
        PyObject function = positional > 1 ? args[1] : PyNone.INSTANCE;
        PyObject initial = PyNone.INSTANCE;
        boolean hasInitial = false;
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                PyObject value = args[positional + i];
                switch (kwNames[i]) {
                    case "iterable" -> iterable = value;
                    case "func" -> function = value;
                    case "initial" -> {
                        initial = value;
                        hasInitial = true;
                    }
                    default -> throw Slots.typeError(
                            "accumulate() got an unexpected keyword argument '%s'", kwNames[i]);
                }
            }
        }
        if (iterable == null) {
            throw Slots.typeError(
                    "accumulate() missing required argument 'iterable' (pos 1)");
        }
        return new Accumulate(iterable, function, initial, hasInitial);
    }

    /** {@code pairwise(iterable)}: each item with the one before it, held in one field. */
    static final class PairWise extends Cursor {
        private final PyIterator source;
        private PyObject previous;
        private boolean primed;

        PairWise(PyObject iterable) {
            super(PyType.PAIRWISE);
            this.source = Slots.iterator(iterable);
        }

        @Override
        public PyObject nextOrNull() {
            if (!primed) {
                primed = true;
                previous = source.nextOrNull();
                if (previous == null) {
                    return null;
                }
            }
            PyObject item = source.nextOrNull();
            if (item == null) {
                return null;
            }
            PyTuple pair = PyTuple.of(previous, item);
            previous = item;
            return pair;
        }
    }

    /** {@code pairwise(iterable, /)}: one positional argument. */
    static PyObject pairing(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("pairwise()", kwNames);
        Arguments.exactly("pairwise", args, kwNames, 1);
        return new PairWise(args[0]);
    }

    /** {@code batched(iterable, n, *, strict=False)}: tuples of up to {@code n}, the last one short. */
    static final class Batched extends Cursor {
        private final PyIterator source;
        private final int size;
        private final boolean strict;
        private boolean done;

        Batched(PyObject iterable, int size, boolean strict) {
            super(PyType.BATCHED);
            this.source = Slots.iterator(iterable);
            this.size = size;
            this.strict = strict;
        }

        @Override
        public PyObject nextOrNull() {
            if (done) {
                return null;
            }
            List<PyObject> batch = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                PyObject item = source.nextOrNull();
                if (item == null) {
                    done = true;
                    break;
                }
                batch.add(item);
            }
            if (batch.isEmpty()) {
                return null;
            }
            if (strict && batch.size() < size) {
                throw Slots.valueError("batched(): incomplete batch");
            }
            return PyTuple.of(batch.toArray(new PyObject[0]));
        }
    }

    /** {@code batched(iterable, n, *, strict=False)}: two positional arguments and one keyword. */
    static PyObject batching(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional > 2) {
            throw Slots.typeError(
                    "batched() takes exactly 2 positional arguments (%d given)", positional);
        }
        if (positional < 1) {
            throw Slots.typeError(
                    "batched() missing required argument 'iterable' (pos 1)");
        }
        PyObject count = positional > 1 ? args[1] : null;
        PyObject strict = PyNone.INSTANCE;
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                PyObject value = args[positional + i];
                switch (kwNames[i]) {
                    case "n" -> count = value;
                    case "strict" -> strict = value;
                    default -> throw Slots.typeError(
                            "batched() got an unexpected keyword argument '%s'", kwNames[i]);
                }
            }
        }
        if (count == null) {
            throw Slots.typeError("batched() missing required argument 'n' (pos 2)");
        }
        BigInteger size = Numbers.asIndex(count);
        if (size.signum() <= 0) {
            throw Slots.valueError("n must be at least one");
        }
        if (size.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw Slots.valueError("n must be at least one");
        }
        return new Batched(args[0], size.intValue(), Slots.truth(strict));
    }

    /** {@code compress(data, selectors)}: the data items whose selector is true, both walked together. */
    static final class Compress extends Cursor {
        private final PyIterator data;
        private final PyIterator selectors;

        Compress(PyObject data, PyObject selectors) {
            super(PyType.COMPRESS);
            this.data = Slots.iterator(data);
            this.selectors = Slots.iterator(selectors);
        }

        @Override
        public PyObject nextOrNull() {
            while (true) {
                PyObject item = data.nextOrNull();
                if (item == null) {
                    return null;
                }
                PyObject selector = selectors.nextOrNull();
                if (selector == null) {
                    return null;
                }
                if (Slots.truth(selector)) {
                    return item;
                }
            }
        }
    }

    /** {@code compress(data, selectors, /)}: two positional arguments. */
    static PyObject compressing(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("compress()", kwNames);
        Arguments.exactly("compress", args, kwNames, 2);
        return new Compress(args[0], args[1]);
    }

    /** {@code dropwhile(predicate, iterable)}: the head the predicate takes, then everything. */
    static final class DropWhile extends Cursor {
        private final PyObject predicate;
        private final PyIterator source;
        private boolean dropping = true;

        DropWhile(PyObject predicate, PyObject iterable) {
            super(PyType.DROPWHILE);
            this.predicate = predicate;
            this.source = Slots.iterator(iterable);
        }

        @Override
        public PyObject nextOrNull() {
            if (!dropping) {
                return source.nextOrNull();
            }
            while (true) {
                PyObject item = source.nextOrNull();
                if (item == null) {
                    return null;
                }
                if (!Slots.truth(Slots.call(predicate, new PyObject[] {item}, null))) {
                    dropping = false;
                    return item;
                }
            }
        }
    }

    /** {@code dropwhile(predicate, iterable, /)}: two positional arguments. */
    static PyObject dropping(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("dropwhile()", kwNames);
        Arguments.exactly("dropwhile", args, kwNames, 2);
        return new DropWhile(args[0], args[1]);
    }

    /** {@code filterfalse(function, iterable)}: the items the predicate rejects, or the false ones. */
    static final class FilterFalse extends Cursor {
        private final PyObject predicate;
        private final PyIterator source;

        FilterFalse(PyObject predicate, PyObject iterable) {
            super(PyType.FILTERFALSE);
            this.predicate = predicate == PyNone.INSTANCE ? null : predicate;
            this.source = Slots.iterator(iterable);
        }

        @Override
        public PyObject nextOrNull() {
            while (true) {
                PyObject item = source.nextOrNull();
                if (item == null) {
                    return null;
                }
                boolean truth = predicate == null
                        ? Slots.truth(item)
                        : Slots.truth(Slots.call(predicate, new PyObject[] {item}, null));
                if (!truth) {
                    return item;
                }
            }
        }
    }

    /** {@code filterfalse(function, iterable, /)}: two positional arguments. */
    static PyObject filteringFalse(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("filterfalse()", kwNames);
        Arguments.exactly("filterfalse", args, kwNames, 2);
        return new FilterFalse(args[0], args[1]);
    }

    /** {@code takewhile(predicate, iterable)}: the head the predicate takes, then nothing. */
    static final class TakeWhile extends Cursor {
        private final PyObject predicate;
        private final PyIterator source;
        private boolean done;

        TakeWhile(PyObject predicate, PyObject iterable) {
            super(PyType.TAKE_WHILE);
            this.predicate = predicate;
            this.source = Slots.iterator(iterable);
        }

        @Override
        public PyObject nextOrNull() {
            if (done) {
                return null;
            }
            PyObject item = source.nextOrNull();
            if (item == null) {
                done = true;
                return null;
            }
            if (!Slots.truth(Slots.call(predicate, new PyObject[] {item}, null))) {
                done = true;
                return null;
            }
            return item;
        }
    }

    /** {@code takewhile(predicate, iterable, /)}: two positional arguments. */
    static PyObject taking(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("takewhile()", kwNames);
        Arguments.exactly("takewhile", args, kwNames, 2);
        return new TakeWhile(args[0], args[1]);
    }

    /** {@code starmap(function, iterable)}: the function called with each item unpacked. */
    static final class StarMap extends Cursor {
        private final PyObject function;
        private final PyIterator source;

        StarMap(PyObject function, PyObject iterable) {
            super(PyType.STAR_MAP);
            this.function = function;
            this.source = Slots.iterator(iterable);
        }

        @Override
        public PyObject nextOrNull() {
            PyObject item = source.nextOrNull();
            if (item == null) {
                return null;
            }
            List<PyObject> arguments = new ArrayList<>();
            PyIterator parts = Slots.iterator(item);
            PyObject part;
            while ((part = parts.nextOrNull()) != null) {
                arguments.add(part);
            }
            return Slots.call(function, arguments.toArray(new PyObject[0]), null);
        }
    }

    /** {@code starmap(function, iterable, /)}: two positional arguments. */
    static PyObject starMapping(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("starmap()", kwNames);
        Arguments.exactly("starmap", args, kwNames, 2);
        return new StarMap(args[0], args[1]);
    }

    /** {@code zip_longest(*iterables, fillvalue=None)}: the sources kept beside the fill value. */
    static final class ZipLongest extends Cursor {
        private final PyIterator[] sources;
        private final PyObject fillValue;
        private boolean done;

        ZipLongest(PyObject[] iterables, PyObject fillValue) {
            super(PyType.ZIP_LONGEST);
            this.fillValue = fillValue;
            this.sources = new PyIterator[iterables.length];
            for (int i = 0; i < iterables.length; i++) {
                sources[i] = Slots.iterator(iterables[i]);
            }
        }

        @Override
        public PyObject nextOrNull() {
            if (done) {
                return null;
            }
            PyObject[] row = new PyObject[sources.length];
            boolean any = false;
            for (int i = 0; i < sources.length; i++) {
                PyObject item = sources[i] == null ? null : sources[i].nextOrNull();
                if (item == null) {
                    sources[i] = null;
                    row[i] = fillValue;
                } else {
                    row[i] = item;
                    any = true;
                }
            }
            if (!any) {
                done = true;
                return null;
            }
            return PyTuple.of(row);
        }
    }

    /** {@code zip_longest(*iterables, fillvalue=None)}: any number of sources, one keyword. */
    static PyObject zipLongest(PyObject[] args, String[] kwNames) {
        PyObject fillValue = PyNone.INSTANCE;
        int positional = Arguments.positional(args, kwNames);
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                if (!kwNames[i].equals("fillvalue")) {
                    throw Slots.typeError(
                            "zip_longest() got an unexpected keyword argument '%s'", kwNames[i]);
                }
                fillValue = args[positional + i];
            }
        }
        PyObject[] iterables = new PyObject[positional];
        System.arraycopy(args, 0, iterables, 0, positional);
        return new ZipLongest(iterables, fillValue);
    }

    /** The items of an iterable, read once: the pool the two combinatorial cursors index into. */
    private static PyObject[] drain(PyObject iterable) {
        List<PyObject> items = new ArrayList<>();
        PyIterator source = Slots.iterator(iterable);
        PyObject item;
        while ((item = source.nextOrNull()) != null) {
            items.add(item);
        }
        return items.toArray(new PyObject[0]);
    }

    /**
     * {@code permutations(iterable, r=None)}: the pool kept, and the index rotations the reference cycles.
     *
     * <p>This is {@code itertoolsmodule.c}'s own algorithm, {@code cycles} and all, so the order is the
     * documented one — every arrangement of the first {@code r} indices before the prefix advances.
     */
    static final class Permutations extends Cursor {
        private final PyObject[] pool;
        private final int r;
        private final int[] indices;
        private final int[] cycles;
        private boolean first = true;
        private boolean done;

        Permutations(PyObject iterable, BigInteger requested) {
            super(PyType.PERMUTATIONS);
            this.pool = drain(iterable);
            if (requested == null) {
                this.r = pool.length;
            } else if (requested.compareTo(BigInteger.valueOf(pool.length)) > 0) {
                // Any r past the pool is an empty result, and keeping the count as n + 1 says so
                // without sizing an array by a number the caller chose.
                this.r = pool.length + 1;
            } else {
                this.r = requested.intValue();
            }
            this.indices = new int[pool.length];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = i;
            }
            this.cycles = new int[Math.max(r, 0)];
            for (int i = 0; i < cycles.length; i++) {
                cycles[i] = pool.length - i;
            }
        }

        @Override
        public PyObject nextOrNull() {
            if (done) {
                return null;
            }
            if (first) {
                first = false;
                if (r > pool.length) {
                    done = true;
                    return null;
                }
                return row();
            }
            for (int i = r - 1; i >= 0; i--) {
                if (--cycles[i] == 0) {
                    int head = indices[i];
                    System.arraycopy(indices, i + 1, indices, i, pool.length - i - 1);
                    indices[pool.length - 1] = head;
                    cycles[i] = pool.length - i;
                } else {
                    int other = pool.length - cycles[i];
                    int swap = indices[i];
                    indices[i] = indices[other];
                    indices[other] = swap;
                    return row();
                }
            }
            done = true;
            return null;
        }

        private PyObject row() {
            PyObject[] items = new PyObject[r];
            for (int i = 0; i < r; i++) {
                items[i] = pool[indices[i]];
            }
            return PyTuple.of(items);
        }
    }

    /** {@code permutations(iterable, r=None)}: the pool is built here, so an iterator is too. */
    static PyObject permuting(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("permutations()", kwNames);
        Arguments.between("permutations", args, kwNames, 1, 2);
        BigInteger r = null;
        if (args.length == 2 && args[1] != PyNone.INSTANCE) {
            r = Numbers.asIndex(args[1]);
            if (r.signum() < 0) {
                throw Slots.valueError("r must be non-negative");
            }
        }
        return new Permutations(args[0], r);
    }

    /** {@code product(*iterables, repeat=1)}: every pool kept, and an odometer reading one index each. */
    static final class Product extends Cursor {
        private final PyObject[][] pools;
        private final int[] indices;
        private boolean first = true;
        private boolean done;

        Product(PyObject[] iterables, int repeat) {
            super(PyType.PRODUCT);
            this.pools = new PyObject[iterables.length * repeat][];
            for (int i = 0; i < pools.length; i++) {
                pools[i] = drain(iterables[i % iterables.length]);
                if (pools[i].length == 0) {
                    done = true;
                }
            }
            this.indices = new int[pools.length];
        }

        @Override
        public PyObject nextOrNull() {
            if (done) {
                return null;
            }
            if (first) {
                first = false;
                return row();
            }
            for (int i = pools.length - 1; i >= 0; i--) {
                if (++indices[i] < pools[i].length) {
                    return row();
                }
                indices[i] = 0;
            }
            done = true;
            return null;
        }

        private PyObject row() {
            PyObject[] items = new PyObject[pools.length];
            for (int i = 0; i < pools.length; i++) {
                items[i] = pools[i][indices[i]];
            }
            return PyTuple.of(items);
        }
    }

    /** {@code product(*iterables, repeat=1)}: any number of pools, one keyword. */
    static PyObject producing(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        BigInteger repeat = BigInteger.ONE;
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                if (!kwNames[i].equals("repeat")) {
                    throw Slots.typeError(
                            "product() got an unexpected keyword argument '%s'", kwNames[i]);
                }
                repeat = Numbers.asIndex(args[positional + i]);
            }
        }
        if (repeat.signum() < 0) {
            throw Slots.valueError("repeat argument cannot be negative");
        }
        PyObject[] iterables = new PyObject[positional];
        System.arraycopy(args, 0, iterables, 0, positional);
        return new Product(iterables, repeat.bitLength() > 31 ? Integer.MAX_VALUE : repeat.intValue());
    }
}
