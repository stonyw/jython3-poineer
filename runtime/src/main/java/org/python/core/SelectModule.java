package org.python.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code select} module, as far as {@code selectors} needs it.
 *
 * <p>The JVM has no {@code select(2)}, but the descriptors {@link Fds} hands out do have the one
 * property the call is asked about: whether a read or a write would not block. So the three lists are
 * walked, each file object is turned into its descriptor, and the ones that are ready are answered in
 * the order they were given. A call with nothing ready polls once and sleeps for a millisecond, which
 * is what a selector loop would do anyway.
 *
 * <p>Only {@code select} is filed. {@code epoll}, {@code kqueue} and {@code poll} are deliberately
 * absent, so {@code selectors.DefaultSelector} settles on {@code SelectSelector} rather than on a
 * back end this runtime cannot provide. The exception-list half of the call is always empty: a Java
 * pipe has no out-of-band data.
 */
final class SelectModule {

    /** {@code PIPE_BUF}: {@code subprocess} writes this much at a time without risking a block. */
    private static final int PIPE_BUF = 512;

    private static final String DOC =
            "This module provides access to the select() and poll() functions available in most\n"
                    + "operating systems.";

    private SelectModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("select");
        module.set("__doc__", PyStr.of(DOC));
        PyRuntime.markBuiltIn(module, "select");
        module.set("error", ExceptionTypes.OS_ERROR);
        module.set("PIPE_BUF", PyInt.of(PIPE_BUF));
        module.set("select", new PyNativeFunction("select", SelectModule::select).documented(
                "select(rlist, wlist, xlist[, timeout]) -> (rlist, wlist, xlist)\n"
                        + "--\n\n"
                        + "Wait until one or more file descriptors are ready for some kind of I/O."));
        return module;
    }

    /** One file object and the descriptor it named when the call started. */
    private record Entry(PyObject object, int fd) {
    }

    private static PyObject select(PyObject[] args, String[] kwNames) {
        Arguments.noKeywords("select.select()", kwNames);
        Arguments.between("select", args, kwNames, 3, 4);
        List<Entry> reading = entries(args[0]);
        List<Entry> writing = entries(args[1]);
        List<Entry> exceptional = entries(args[2]);
        boolean blocking = args.length < 4 || args[3] == PyNone.INSTANCE;
        double timeout = blocking ? -1 : Numeric.asDouble(args[3]);

        long deadline = timeout > 0
                ? System.nanoTime() + (long) (timeout * 1_000_000_000L)
                : 0;
        while (true) {
            List<PyObject> readable = ready(reading, true);
            List<PyObject> writable = ready(writing, false);
            if (!readable.isEmpty() || !writable.isEmpty() || (!blocking && timeout <= 0)) {
                return answer(readable, writable, exceptional);
            }
            if (!blocking && System.nanoTime() >= deadline) {
                return answer(new ArrayList<>(), new ArrayList<>(), exceptional);
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return answer(new ArrayList<>(), new ArrayList<>(), exceptional);
            }
        }
    }

    private static PyObject answer(
            List<PyObject> readable, List<PyObject> writable, List<Entry> exceptional) {
        return PyTuple.of(list(readable), list(writable), PyList.empty());
    }

    private static PyList list(List<PyObject> items) {
        return PyList.of(items.toArray(new PyObject[0]));
    }

    private static List<PyObject> ready(List<Entry> entries, boolean reading) {
        List<PyObject> ready = new ArrayList<>();
        for (Entry entry : entries) {
            if (reading ? Fds.readable(entry.fd) : Fds.writable(entry.fd)) {
                ready.add(entry.object);
            }
        }
        return ready;
    }

    /** Turns one argument into descriptors, keeping the objects to answer with. */
    private static List<Entry> entries(PyObject sequence) {
        List<Entry> entries = new ArrayList<>();
        for (PyObject item : items(sequence)) {
            entries.add(new Entry(item, descriptor(item)));
        }
        return entries;
    }

    private static List<PyObject> items(PyObject sequence) {
        if (sequence instanceof PyList list) {
            return list.getItems();
        }
        if (sequence instanceof PyTuple tuple) {
            List<PyObject> items = new ArrayList<>(tuple.size());
            for (int i = 0; i < tuple.size(); i++) {
                items.add(tuple.at(i));
            }
            return items;
        }
        if (sequence instanceof PySet set) {
            return new ArrayList<>(set.getItems());
        }
        throw Slots.typeError("expected a sequence of file descriptors, not "
                + sequence.getType().name());
    }

    /** {@code _fileobj_to_fd}: an integer is its own descriptor, anything else answers {@code fileno()}. */
    private static int descriptor(PyObject object) {
        if (object instanceof PyInt || object instanceof PyBool) {
            return Numbers.asIndex(object).intValue();
        }
        PyObject fileno = Slots.getAttr(object, "fileno");
        return Numbers.asIndex(Slots.call(fileno, new PyObject[0], null)).intValue();
    }
}
