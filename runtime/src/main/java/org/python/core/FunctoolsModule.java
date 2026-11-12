package org.python.core;

import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * The {@code functools} module, in the one shape {@code re} needs.
 *
 * <p>The reference {@code functools} is a Python module over a C accelerator; this runtime files a
 * built-in of the same name because {@code re/__init__.py} imports {@code lru_cache} at module scope
 * and the pure-Python {@code functools} cannot be imported yet. Only {@code lru_cache}, {@code cache}
 * and the wrapper they build are provided — enough for {@code re.purge()} and for the template cache
 * behind {@code sub}/{@code expand}.
 */
final class FunctoolsModule {

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
        return module;
    }

    /** {@code lru_cache(maxsize=128, typed=False)}: the reference's decorator, minus the statistics. */
    private static PyObject lruCache(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.fill("lru_cache", args, kwNames,
                new String[] {"maxsize", "typed"},
                new PyObject[] {PyInt.of(128), PyBool.FALSE});
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
}
