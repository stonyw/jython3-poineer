package org.python.core;

import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * The {@code functools} module, in the one shape {@code re} needs.
 *
 * <p>The reference {@code functools} is a Python module over a C accelerator; this runtime files a
 * built-in of the same name because {@code re/__init__.py} imports {@code lru_cache} at module scope
 * and the pure-Python {@code functools} cannot be imported yet. {@code lru_cache}, {@code cache} and the
 * wrapper they build are provided, plus {@code update_wrapper}/{@code wraps} because
 * {@code contextlib} imports {@code wraps} before it will define a single context manager.
 */
final class FunctoolsModule {

    /** {@code WRAPPER_ASSIGNMENTS}, spelled as 3.14 spells it. */
    private static final PyTuple WRAPPER_ASSIGNMENTS = PyTuple.of(
            PyStr.of("__module__"),
            PyStr.of("__name__"),
            PyStr.of("__qualname__"),
            PyStr.of("__doc__"),
            PyStr.of("__annotate__"),
            PyStr.of("__type_params__"));

    private static final PyTuple WRAPPER_UPDATES = PyTuple.of(PyStr.of("__dict__"));

    private FunctoolsModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("functools");
        module.set("__doc__", PyStr.of("functools: higher-order functions and operations on callables."));
        module.set("lru_cache", new PyNativeFunction("lru_cache", FunctoolsModule::lruCache));
        module.set("cache", new PyNativeFunction("cache", (args, kwNames) -> {
            Arguments.exactlyOne("cache", args, kwNames);
            return new Wrapper(args[0], -1);
        }));
        module.set("partial", PyType.PARTIAL);
        module.set("WRAPPER_ASSIGNMENTS", WRAPPER_ASSIGNMENTS);
        module.set("WRAPPER_UPDATES", WRAPPER_UPDATES);
        module.set("update_wrapper", new PyNativeFunction("update_wrapper",
                FunctoolsModule::updateWrapper));
        module.set("wraps", new PyNativeFunction("wraps", FunctoolsModule::wraps));
        return module;
    }

    /**
     * {@code partial(func, /, *args, **keywords)}: the arguments a call is given are bound now.
     *
     * <p>{@code func} is positional-only, which is why the count checked here is of the positional
     * arguments rather than of all of them: {@code partial(func=len)} is refused in the reference too.
     */
    static PyObject newPartial(PyObject[] args, String[] kwNames) {
        int names = kwNames == null ? 0 : kwNames.length;
        int positional = args.length - names;
        if (positional < 1) {
            throw Slots.typeError("type 'partial' takes at least one argument");
        }
        PyObject[] bound = new PyObject[positional - 1];
        System.arraycopy(args, 1, bound, 0, bound.length);
        PyDict keywords = PyDict.empty();
        for (int i = 0; i < names; i++) {
            keywords.put(PyStr.interned(kwNames[i]), args[positional + i]);
        }
        return new Partial(args[0], bound, keywords);
    }

    private static PyObject updateWrapper(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.parameters("update_wrapper", args, kwNames,
                new String[] {"wrapper", "wrapped", "assigned", "updated"},
                new PyObject[] {null, null, WRAPPER_ASSIGNMENTS, WRAPPER_UPDATES}, 2);
        return copyWrapper(values[0], values[1], values[2], values[3]);
    }

    /** The decorator factory {@code functools.wraps}: the arguments are bound now, the copy happens then. */
    private static PyObject wraps(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.parameters("wraps", args, kwNames,
                new String[] {"wrapped", "assigned", "updated"},
                new PyObject[] {null, WRAPPER_ASSIGNMENTS, WRAPPER_UPDATES}, 1);
        return new PyNativeFunction("decorator", (callArgs, callNames) -> {
            Arguments.exactlyOne("decorator", callArgs, callNames);
            return copyWrapper(callArgs[0], values[0], values[1], values[2]);
        });
    }

    /**
     * {@code update_wrapper} itself: each named attribute of the wrapped function that exists is written
     * onto the wrapper, the named dictionaries are merged into the wrapper's own, and {@code
     * __wrapped__} is set last so that the merge cannot copy one across.
     */
    private static PyObject copyWrapper(
            PyObject wrapper, PyObject wrapped, PyObject assigned, PyObject updated) {
        for (PyObject name : elements(assigned)) {
            String attribute = text(name);
            PyObject value = wrapped.getAttr(attribute);
            if (value != Slots.DECLINED) {
                wrapper.setAttr(attribute, value);
            }
        }
        for (PyObject name : elements(updated)) {
            if (!text(name).equals("__dict__")) {
                continue;
            }
            PyObject source = wrapped.getAttr("__dict__");
            PyObject target = wrapper.getAttr("__dict__");
            if (source instanceof PyDict from && target instanceof PyDict to) {
                PyIterator keys = Slots.iterator(from);
                PyObject key;
                while ((key = keys.nextOrNull()) != null) {
                    to.put(key, from.get(key));
                }
            }
        }
        wrapper.setAttr("__wrapped__", wrapped);
        return wrapper;
    }

    private static java.util.List<PyObject> elements(PyObject iterable) {
        java.util.List<PyObject> items = new java.util.ArrayList<>();
        PyIterator iterator = Slots.iterator(iterable);
        PyObject item;
        while ((item = iterator.nextOrNull()) != null) {
            items.add(item);
        }
        return items;
    }

    private static String text(PyObject name) {
        if (name instanceof PyStr string) {
            return string.getValue();
        }
        throw Slots.typeError("attribute name must be string, not '%s'", name.getType().name());
    }

    /**
     * {@code lru_cache(maxsize=128, typed=False)}: the reference's decorator, minus the statistics.
     *
     * <p>The first argument may be the function itself, which is the bare {@code @lru_cache} spelling
     * 3.14 accepts: a callable maxsize means "the defaults, wrapping this function", as CPython's own
     * {@code elif callable(maxsize)} arm decides.
     */
    private static PyObject lruCache(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.fill("lru_cache", args, kwNames,
                new String[] {"maxsize", "typed"},
                new PyObject[] {PyInt.of(128), PyBool.FALSE});
        if (Slots.isCallable(values[0])) {
            return new Wrapper(values[0], 128);
        }
        int maxsize = values[0] instanceof PyNone ? -1 : Numbers.asIndex(values[0]).intValue();
        return new PyNativeFunction("decorator", (callArgs, callNames) -> {
            Arguments.exactlyOne("decorator", callArgs, callNames);
            return new Wrapper(callArgs[0], maxsize);
        });
    }

    /**
     * One memoising wrapper, which is also the type {@code _lru_cache_wrapper}.
     *
     * <p>Keys are built out of the arguments themselves, so a value that can be a dictionary key can
     * be a cache key — including the {@link PySrePattern} that {@code re}'s template cache is keyed
     * by, whose hash is its identity.
     */
    static final class Wrapper extends PyObject {

        private static final Members METHODS = Members.of(PyType.LRU_CACHE_WRAPPER)
                .addField("__wrapped__", "The function this wrapper was made from.",
                        self -> ((Wrapper) self).function)
                .addNoArgument("cache_clear", self -> {
                    ((Wrapper) self).clear();
                    return PyNone.INSTANCE;
                })
                .addNoArgument("cache_info", self -> ((Wrapper) self).info());

        private final PyObject function;

        /** Zero means do not cache, a negative value means no bound. */
        private final int maxsize;

        private final LinkedHashMap<PyObject, PyObject> cache = new LinkedHashMap<>();

        private long hits;

        private long misses;

        Wrapper(PyObject function, int maxsize) {
            super(PyType.LRU_CACHE_WRAPPER);
            this.function = function;
            this.maxsize = maxsize;
        }

        private static PyObject keyOf(PyObject[] args, PyTuple kwNames) {
            int extra = kwNames == null ? 0 : kwNames.size();
            if (extra == 0) {
                return PyTuple.of(args);
            }
            PyObject[] parts = new PyObject[args.length + extra];
            System.arraycopy(args, 0, parts, 0, args.length);
            for (int i = 0; i < extra; i++) {
                parts[args.length + i] = kwNames.at(i);
            }
            return PyTuple.of(parts);
        }

        @Override
        public PyObject call(PyObject[] args, PyTuple kwNames) {
            if (maxsize == 0) {
                return Slots.call(function, args, kwNames);
            }
            PyObject key = keyOf(args, kwNames);
            PyObject cached = cache.get(key);
            if (cached != null) {
                hits++;
                return cached;
            }
            PyObject value = Slots.call(function, args, kwNames);
            misses++;
            if (maxsize > 0 && cache.size() >= maxsize) {
                Iterator<PyObject> eldest = cache.keySet().iterator();
                eldest.next();
                eldest.remove();
            }
            cache.put(key, value);
            return value;
        }

        private void clear() {
            cache.clear();
            hits = 0;
            misses = 0;
        }

        private PyObject info() {
            return PyTuple.of(PyInt.of(maxsize < 0 ? -1 : maxsize), PyInt.of(cache.size()),
                    PyInt.of(hits), PyInt.of(misses));
        }

        @Override
        public long hash() {
            return System.identityHashCode(this);
        }
    }

    /**
     * {@code functools.partial}: a callable with some of its arguments bound.
     *
     * <p>The bound arguments go in front of the ones the call supplies, and the bound keywords are
     * overridden by the call's, which is the merge {@code partial_call} performs. Pickling a partial is a
     * named gap: no {@code __reduce__} is filed, so {@code pickle} has nothing to write it with.
     */
    static final class Partial extends PyObject {

        private static final Members METHODS = Members.of(PyType.PARTIAL)
                .addField("func", "The function to be used by this partial.",
                        self -> ((Partial) self).function)
                .addField("args", "The positional arguments to be used by this partial.",
                        self -> PyTuple.of(((Partial) self).arguments))
                .addField("keywords", "The keyword arguments to be used by this partial.",
                        self -> ((Partial) self).keywords);

        private final PyObject function;

        private final PyObject[] arguments;

        private final PyDict keywords;

        Partial(PyObject function, PyObject[] arguments, PyDict keywords) {
            super(PyType.PARTIAL);
            this.function = function;
            this.arguments = arguments;
            this.keywords = keywords;
        }

        @Override
        public PyObject call(PyObject[] args, PyTuple kwNames) {
            int names = kwNames == null ? 0 : kwNames.size();
            int positional = args.length - names;
            PyObject[] all = new PyObject[arguments.length + positional];
            System.arraycopy(arguments, 0, all, 0, arguments.length);
            System.arraycopy(args, 0, all, arguments.length, positional);
            if (names == 0 && keywords.size() == 0) {
                return Slots.call(function, all, null);
            }
            PyDict merged = PyDict.empty();
            PyIterator stored = Slots.iterator(keywords);
            PyObject key;
            while ((key = stored.nextOrNull()) != null) {
                merged.put(key, keywords.get(key));
            }
            for (int i = 0; i < names; i++) {
                merged.put(kwNames.at(i), args[positional + i]);
            }
            PyObject[] mergedNames = new PyObject[merged.size()];
            PyObject[] values = new PyObject[merged.size()];
            PyObject[] callArgs = new PyObject[all.length + merged.size()];
            System.arraycopy(all, 0, callArgs, 0, all.length);
            PyIterator entries = Slots.iterator(merged);
            int index = 0;
            while ((key = entries.nextOrNull()) != null) {
                mergedNames[index] = key;
                values[index] = merged.get(key);
                index++;
            }
            System.arraycopy(values, 0, callArgs, all.length, values.length);
            return Slots.call(function, callArgs, PyTuple.of(mergedNames));
        }

        @Override
        public boolean isCallable() {
            return true;
        }

        @Override
        public String repr() {
            StringBuilder text = new StringBuilder("functools.partial(").append(Slots.repr(function));
            for (PyObject argument : arguments) {
                text.append(", ").append(Slots.repr(argument));
            }
            PyIterator keys = Slots.iterator(keywords);
            PyObject key;
            while ((key = keys.nextOrNull()) != null) {
                text.append(", ").append(Slots.str(key)).append('=')
                        .append(Slots.repr(keywords.get(key)));
            }
            return text.append(')').toString();
        }
    }
}
