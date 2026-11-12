package org.python.core;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * {@code _random}: the Mersenne Twister that {@code random.py} subclasses.
 *
 * <p>Every number is the reference's bit for bit — the 624-word state, the twist, the
 * {@code genrand_uint32} tempering, the 53-bit double and the seeding walk are transcribed from
 * {@code Modules/_randommodule.c}, so a program that seeds with an integer reads the same stream it
 * would on CPython. That matters because the whole standard library's reproducibility rests on it:
 * {@code random.seed(0); random.random()} is {@code 0.8444218515250481} in both runtimes.
 *
 * <p>Seeding from something that is not an int goes through {@code hash()} exactly as the reference
 * does — which is bit-identical for floats and small ints, and is this runtime's own hash for strings.
 * {@code random.seed} never takes that path for a string, because {@code random.py} folds it into an
 * integer with SHA-512 first; the fallback is only reached by a program that seeds a float directly,
 * and a float's hash here is CPython's.
 *
 * <p>The type is a base a Python class may take, because {@code random.Random} is written
 * {@code class Random(_random.Random)}. The state lives in Java beside the subclass's dictionary, the
 * same split {@code weakref.ref} makes, and the six rows are read through {@link Members} so that a
 * subclass declaring none of them still answers with this table.
 */
final class RandomModule {

    /** {@code N}: the length of the state vector. */
    private static final int N = 624;

    /** {@code M}: the offset of the twist. */
    private static final int M = 397;

    private static final int MATRIX_A = 0x9908b0df;
    private static final int UPPER_MASK = 0x80000000;
    private static final int LOWER_MASK = 0x7fffffff;

    private static final BigInteger TWO_TO_32 = BigInteger.ONE.shiftLeft(32);

    private static final String DOC =
            "Module implements the Mersenne Twister random number generator.";

    private static final String RANDOM_DOC = "random() -> x in the interval [0, 1).";

    private static final String SEED_DOC =
            "seed([n]) -> None.\n"
                    + "\n"
                    + "Defaults to use urandom and falls back to a combination\n"
                    + "of the current time and the process identifier.";

    private static final String GETSTATE_DOC = "getstate() -> tuple containing the current state.";

    private static final String SETSTATE_DOC = "setstate(state) -> None.  Restores generator state.";

    private static final String GETRANDBITS_DOC =
            "getrandbits(k) -> x.  Generates an int with k random bits.";

    private RandomModule() {
    }

    /**
     * The four words-and-index operations a generator answers, whichever Java shape it has.
     *
     * <p>The plain {@link Random} and the {@link Subclass} a class written over it makes share the
     * bodies because the state is this interface's, not the value's: the reference's {@code mt} array
     * is one field of the base struct and a subclass inherits it.
     */
    interface Generator {

        int[] state();

        int index();

        void setIndex(int index);
    }

    /** {@code _random.Random} itself: a value with the state and nothing else. */
    static final class Random extends PyObject implements Generator {

        private final int[] state = new int[N];

        private int index = N;

        Random() {
            super(PyType.RANDOM);
        }

        @Override
        public PyObject getAttr(String attribute) {
            return Members.find(this, attribute);
        }

        @Override
        public int[] state() {
            return state;
        }

        @Override
        public int index() {
            return index;
        }

        @Override
        public void setIndex(int index) {
            this.index = index;
        }
    }

    /**
     * A value of a class Python wrote over {@code _random.Random}, which is what {@code random.Random}
     * is.
     *
     * <p>CPython's type is static, so a class written over it is an ordinary heap type and the state
     * stays in the base's fields. Here the base is a plain Java object with no room for a dictionary,
     * so a subclass is a {@link PyInstance} that carries the state beside the dictionary; both answer
     * the same six operations because they are written against {@link Generator}.
     */
    static final class Subclass extends PyInstance implements Generator {

        private final int[] state = new int[N];

        private int index = N;

        Subclass(PyType kind) {
            super(kind);
        }

        @Override
        public int[] state() {
            return state;
        }

        @Override
        public int index() {
            return index;
        }

        @Override
        public void setIndex(int index) {
            this.index = index;
        }

        /**
         * The names inherited from the generator's own table, which {@link PyInstance}'s walk of the
         * class namespace cannot see. A name the class filed itself is answered before this hook.
         */
        @Override
        PyObject inherited(String name) {
            PyObject method = Members.find(this, name);
            return method != Slots.DECLINED ? method : TypeDict.read(this, name);
        }
    }

    /** The six rows {@code _random.Random} files, in the reference's own order. */
    static final Members ROWS = Members.of(PyType.RANDOM)
            .add("random", RANDOM_DOC, RandomModule::random)
            .add("seed", SEED_DOC, RandomModule::seedMethod)
            .add("getstate", GETSTATE_DOC, RandomModule::getState)
            .add("setstate", SETSTATE_DOC, RandomModule::setState)
            .add("getrandbits", GETRANDBITS_DOC, RandomModule::getRandBits);

    static PyModule newModule() {
        PyModule module = new PyModule("_random");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "_random");
        module.set("Random", PyType.RANDOM);
        return module;
    }

    /**
     * {@code Random(seed)}: the reference does the whole thing in {@code tp_new}, which is why
     * {@code _random.Random} inherits {@code object.__init__} and why this is a constructor rather
     * than an initialiser.
     */
    static PyObject constructing(PyObject[] args, String[] kwNames) {
        return building(null, args, kwNames);
    }

    /**
     * {@code Random(seed)} for a class Python wrote over the base: the seed is read the same way and
     * the state is kept beside the subclass's dictionary.
     */
    static PyObject constructing(PyType kind, PyObject[] args, String[] kwNames) {
        return building(kind, args, kwNames);
    }

    private static PyObject building(PyType kind, PyObject[] args, String[] kwNames) {
        Generator generator = kind == null ? new Random() : new Subclass(kind);
        initialise(generator, args, kwNames, kind == null ? "Random" : kind.name());
        return (PyObject) generator;
    }

    private static void initialise(Generator generator, PyObject[] args, String[] kwNames, String type) {
        if (kwNames != null) {
            throw Slots.typeError(type + "() takes no keyword arguments");
        }
        if (args.length > 1) {
            throw Slots.typeError(type + " expected at most 1 argument, got %d", args.length);
        }
        seed(generator, args.length == 0 ? null : args[0]);
    }

    private static PyObject random(PyObject self, PyObject[] args, String[] kwNames) {
        nothing("Random.random()", args, kwNames);
        Generator generator = (Generator) self;
        // `random_random` draws two words and keeps 27 and 26 bits of them, which is the shortest
        // path to a double that is uniform over the 2**53 values it can name.
        long a = (next(generator) & 0xFFFFFFFFL) >>> 5;
        long b = (next(generator) & 0xFFFFFFFFL) >>> 6;
        return PyFloat.of((a * 67108864.0 + b) / 9007199254740992.0);
    }

    private static PyObject seedMethod(PyObject self, PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("Random.seed() takes no keyword arguments");
        }
        if (args.length > 1) {
            throw Slots.typeError("seed expected at most 1 argument, got %d", args.length);
        }
        seed((Generator) self, args.length == 0 ? null : args[0]);
        return PyNone.INSTANCE;
    }

    /**
     * {@code random_seed}: an int seeds from its own bits, little-endian, thirty-two at a time; no
     * argument seeds from {@code urandom}; anything else is hashed and its unsigned value used.
     */
    private static void seed(Generator generator, PyObject argument) {
        if (argument == null || argument == PyNone.INSTANCE) {
            seedFromUrandom(generator);
            return;
        }
        BigInteger number = integer(argument);
        if (number == null) {
            // `random_seed` hands the hash to `PyLong_FromSize_t`, so a negative hash seeds as the
            // unsigned 64-bit value it is rather than as its absolute value.
            String unsigned = Long.toUnsignedString(argument.hash());
            seedFromWords(generator, new BigInteger(unsigned));
            return;
        }
        seedFromWords(generator, number.abs());
    }

    private static void seedFromWords(Generator generator, BigInteger number) {
        int[] key;
        if (number.signum() == 0) {
            key = new int[] {0};
        } else {
            int count = (number.bitLength() - 1) / 32 + 1;
            key = new int[count];
            BigInteger rest = number;
            for (int i = 0; i < count; i++) {
                key[i] = rest.and(TWO_TO_32.subtract(BigInteger.ONE)).intValue();
                rest = rest.shiftRight(32);
            }
        }
        initialiseByArray(generator, key);
    }

    /**
     * {@code init_by_array}: the reference's own seeding, which mixes the key into the state twice so
     * that two nearby seeds do not produce streams that start out alike.
     */
    private static void initialiseByArray(Generator generator, int[] key) {
        int[] mt = generator.state();
        initialiseGenrand(mt, 19650218);
        int i = 1;
        int j = 0;
        for (int k = Math.max(N, key.length); k > 0; k--) {
            mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1664525)) + key[j] + j;
            i++;
            j++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
            if (j >= key.length) {
                j = 0;
            }
        }
        for (int k = N - 1; k > 0; k--) {
            mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >>> 30)) * 1566083941)) - i;
            i++;
            if (i >= N) {
                mt[0] = mt[N - 1];
                i = 1;
            }
        }
        mt[0] = 0x80000000;
        generator.setIndex(N);
    }

    private static void initialiseGenrand(int[] mt, int seed) {
        mt[0] = seed;
        for (int i = 1; i < N; i++) {
            mt[i] = 1812433253 * (mt[i - 1] ^ (mt[i - 1] >>> 30)) + i;
        }
    }

    /** {@code random_seed} with no argument: 624 words from the system's entropy source. */
    private static void seedFromUrandom(Generator generator) {
        SecureRandom source = new SecureRandom();
        int[] key = new int[N];
        for (int i = 0; i < N; i++) {
            key[i] = source.nextInt();
        }
        initialiseByArray(generator, key);
    }

    private static PyObject getState(PyObject self, PyObject[] args, String[] kwNames) {
        nothing("Random.getstate()", args, kwNames);
        Generator generator = (Generator) self;
        int[] mt = generator.state();
        PyObject[] words = new PyObject[N + 1];
        for (int i = 0; i < N; i++) {
            words[i] = PyInt.of((mt[i] & 0xFFFFFFFFL));
        }
        words[N] = PyInt.of(generator.index());
        return PyTuple.of(words);
    }

    private static PyObject setState(PyObject self, PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("Random.setstate() takes no keyword arguments");
        }
        if (args.length != 1) {
            throw Slots.typeError(
                    "Random.setstate() takes exactly one argument (%d given)", args.length);
        }
        if (!(args[0] instanceof PyTuple state)) {
            throw Slots.typeError("state vector must be a tuple");
        }
        if (state.size() != N + 1) {
            throw Slots.valueError("state vector is the wrong size");
        }
        int[] mt = ((Generator) self).state();
        PyObject[] items = state.getArray();
        for (int i = 0; i < N; i++) {
            mt[i] = word(items[i]);
        }
        BigInteger index = integer(items[N]);
        if (index == null) {
            index = Numbers.asIndex(items[N]);
        }
        if (index.signum() < 0 || index.bitLength() > 64) {
            throw Slots.overflowError(
                    index.signum() < 0
                            ? "can't convert negative value to unsigned int"
                            : "Python int too large to convert to C unsigned long");
        }
        if (index.compareTo(BigInteger.valueOf(N)) > 0) {
            throw Slots.valueError("state index out of range");
        }
        ((Generator) self).setIndex(index.intValue());
        return PyNone.INSTANCE;
    }

    /** One state word: the reference reads it as an unsigned long and keeps its low 32 bits. */
    private static int word(PyObject item) {
        BigInteger value = integer(item);
        if (value == null) {
            value = Numbers.asIndex(item);
        }
        if (value.signum() < 0) {
            throw Slots.overflowError("can't convert negative value to unsigned int");
        }
        if (value.bitLength() > 64) {
            throw Slots.overflowError("Python int too large to convert to C unsigned long");
        }
        return value.intValue();
    }

    private static PyObject getRandBits(PyObject self, PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError("Random.getrandbits() takes no keyword arguments");
        }
        if (args.length != 1) {
            throw Slots.typeError(
                    "Random.getrandbits() takes exactly one argument (%d given)", args.length);
        }
        BigInteger k = integer(args[0]);
        if (k == null) {
            k = Numbers.asIndex(args[0]);
        }
        if (k.signum() < 0) {
            throw Slots.valueError("Cannot convert negative int");
        }
        Generator generator = (Generator) self;
        if (k.compareTo(BigInteger.valueOf(32)) <= 0) {
            if (k.signum() == 0) {
                return PyInt.of(0L);
            }
            int bits = k.intValue();
            long word = next(generator) & 0xFFFFFFFFL;
            return PyInt.of(word >>> (32 - bits));
        }
        // More than one word: taken least-significant first, and the top word trimmed to the bits
        // that were asked for rather than to a whole word.
        BigInteger answer = BigInteger.ZERO;
        BigInteger remaining = k;
        int shift = 0;
        while (remaining.signum() > 0) {
            int take = remaining.min(BigInteger.valueOf(32)).intValue();
            long word = next(generator) & 0xFFFFFFFFL;
            if (take < 32) {
                word >>>= 32 - take;
            }
            answer = answer.or(BigInteger.valueOf(word).shiftLeft(shift));
            remaining = remaining.subtract(BigInteger.valueOf(take));
            shift += take;
        }
        return PyInt.of(answer);
    }

    /** {@code genrand_uint32}: twist when the state has run out, then temper the next word. */
    private static int next(Generator generator) {
        if (generator.index() >= N) {
            twist(generator);
        }
        int[] mt = generator.state();
        int y = mt[generator.index()];
        generator.setIndex(generator.index() + 1);
        y ^= y >>> 11;
        y ^= (y << 7) & 0x9d2c5680;
        y ^= (y << 15) & 0xefc60000;
        y ^= y >>> 18;
        return y;
    }

    /** {@code init_genrand}'s neighbour: the state shifted by {@code M}, done in place. */
    private static void twist(Generator generator) {
        int[] mt = generator.state();
        int kk;
        for (kk = 0; kk < N - M; kk++) {
            int y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
            mt[kk] = mt[kk + M] ^ (y >>> 1) ^ ((y & 1) != 0 ? MATRIX_A : 0);
        }
        for (; kk < N - 1; kk++) {
            int y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
            mt[kk] = mt[kk + (M - N)] ^ (y >>> 1) ^ ((y & 1) != 0 ? MATRIX_A : 0);
        }
        int y = (mt[N - 1] & UPPER_MASK) | (mt[0] & LOWER_MASK);
        mt[N - 1] = mt[M - 1] ^ (y >>> 1) ^ ((y & 1) != 0 ? MATRIX_A : 0);
        generator.setIndex(0);
    }

    /**
     * The argument as a Python integer, or null when it is not one.
     *
     * <p>{@code bool} is a separate Java class here but a {@code PyLong} subclass in the reference,
     * where {@code PyLong_Check} lets it through as the integer it stands for.
     */
    private static BigInteger integer(PyObject value) {
        if (value instanceof PyBool flag) {
            return flag.getValue() ? BigInteger.ONE : BigInteger.ZERO;
        }
        return Numbers.asInteger(value);
    }

    private static void nothing(String qualified, PyObject[] args, String[] kwNames) {
        if (kwNames != null) {
            throw Slots.typeError(qualified + " takes no keyword arguments");
        }
        if (args.length != 0) {
            throw Slots.typeError(qualified + " takes no arguments (%d given)", args.length);
        }
    }
}
