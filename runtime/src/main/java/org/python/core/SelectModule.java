package org.python.core;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code select} module, as far as {@code selectors} needs it.
 *
 * <p>The JVM has no {@code select(2)}, so the two kinds of number this runtime hands out are asked
 * their own way. A descriptor from {@link Fds} answers from its pipe, which is what {@link
 * Fds#readable} and {@link Fds#writable} already say. A socket number belongs to {@link SocketModule}
 * and is not in that table at all, so it is waited on with the JDK's own {@link Selector}: the channel
 * behind it is registered and the call blocks until the operating system reports it ready. That
 * second half matters — the earlier version asked {@code Fds} about socket numbers and got "ready" for
 * every one of them, which made every selector loop spin at full speed and never sleep.
 *
 * <p>Only {@code select} is filed. {@code epoll}, {@code kqueue} and {@code poll} are deliberately
 * absent, so {@code selectors.DefaultSelector} settles on {@code SelectSelector} rather than on a back
 * end this runtime cannot provide. The exception-list half of the call is always empty: a Java pipe
 * has no out-of-band data.
 */
final class SelectModule {

    /** {@code PIPE_BUF}: {@code subprocess} writes this much at a time without risking a block. */
    private static final int PIPE_BUF = 512;

    private static final int EBADF = 9;

    /**
     * The longest a call with a pipe among its descriptors waits in one round.
     *
     * <p>Pipes have no selector to be woken by, so they are polled; a millisecond is the same interval
     * the loop used before sockets were waited on properly.
     */
    private static final long PIPE_POLL_MILLIS = 1;

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
        checkDescriptors(reading, writing, exceptional);

        long deadline = timeout > 0 ? System.nanoTime() + (long) (timeout * 1_000_000_000L) : 0;
        boolean pipes = hasPipes(reading) || hasPipes(writing);
        try (Selector selector = Selector.open()) {
            register(selector, reading, writing);
            while (true) {
                waitOnce(selector, blocking, timeout, deadline, pipes);
                Ready readyFds = selected(selector);
                List<PyObject> readable = collect(reading, readyFds.reading, true);
                List<PyObject> writable = collect(writing, readyFds.writing, false);
                if (!readable.isEmpty() || !writable.isEmpty()) {
                    return answer(readable, writable);
                }
                if (!blocking && timeout <= 0) {
                    return answer(new ArrayList<>(), new ArrayList<>());
                }
                if (!blocking && System.nanoTime() >= deadline) {
                    return answer(new ArrayList<>(), new ArrayList<>());
                }
            }
        } catch (IOException failure) {
            throw badDescriptor();
        }
    }

    /** Registers every socket descriptor with its interest; a handle with no channel waits forever. */
    private static void register(Selector selector, List<Entry> reading, List<Entry> writing)
            throws IOException {
        Map<Integer, Integer> interests = new LinkedHashMap<>();
        for (Entry entry : reading) {
            if (SocketModule.owns(entry.fd())) {
                interests.merge(entry.fd(), SelectionKey.OP_READ, (left, right) -> left | right);
            }
        }
        for (Entry entry : writing) {
            if (SocketModule.owns(entry.fd())) {
                interests.merge(entry.fd(), SelectionKey.OP_WRITE, (left, right) -> left | right);
            }
        }
        for (Map.Entry<Integer, Integer> wanted : interests.entrySet()) {
            SelectableChannel channel = SocketModule.channelFor(wanted.getKey());
            if (channel == null) {
                continue;
            }
            int ops = wanted.getValue();
            if (channel instanceof java.nio.channels.ServerSocketChannel) {
                // A listening socket is "readable" to the kernel when a connection is pending, and
                // Java names that bit accept. It has no write readiness at all.
                ops = (ops & SelectionKey.OP_READ) != 0 ? SelectionKey.OP_ACCEPT : 0;
            } else if (channel instanceof java.nio.channels.SocketChannel stream
                    && stream.isConnectionPending()) {
                // A kqueue selector never reports write readiness for a connect still in flight; the
                // bit that does fire is connect, so that is what a writer is registered for.
                ops = (ops & SelectionKey.OP_WRITE) != 0 ? SelectionKey.OP_CONNECT : 0;
            }
            // A channel only accepts the bits its own kind can report; asking for another is an
            // IllegalArgumentException rather than a never-ready key.
            ops &= channel.validOps();
            if (ops == 0) {
                continue;
            }
            try {
                channel.register(selector, ops, wanted.getKey());
            } catch (ClosedChannelException closed) {
                throw badDescriptor();
            }
        }
    }

    /** One wait: the selector's own, or a sleep when there is nothing for it to watch. */
    private static void waitOnce(
            Selector selector, boolean blocking, double timeout, long deadline, boolean pipes) {
        long wait = blocking ? -1 : millisLeft(timeout, deadline);
        if (pipes) {
            // A pipe can become ready while the selector sleeps, and nothing here would wake it.
            wait = wait < 0 ? PIPE_POLL_MILLIS : Math.min(wait, PIPE_POLL_MILLIS);
        }
        try {
            if (selector.keys().isEmpty()) {
                sleep(wait);
            } else if (wait < 0) {
                selector.select();
            } else if (wait == 0) {
                selector.selectNow();
            } else {
                selector.select(wait);
            }
        } catch (IOException failure) {
            throw badDescriptor();
        }
    }

    private static long millisLeft(double timeout, long deadline) {
        if (timeout <= 0) {
            return 0;
        }
        long remain = deadline - System.nanoTime();
        if (remain <= 0) {
            return 0;
        }
        return Math.max(1, (remain + 999_999L) / 1_000_000L);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis <= 0 ? 1 : millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Which descriptors the selector reported in this round, split by direction. */
    private record Ready(Set<Integer> reading, Set<Integer> writing) {
    }

    private static Ready selected(Selector selector) {
        Set<Integer> reading = new HashSet<>();
        Set<Integer> writing = new HashSet<>();
        for (SelectionKey key : selector.selectedKeys()) {
            if (!(key.attachment() instanceof Integer fd)) {
                continue;
            }
            int ops = key.readyOps();
            // Accept readiness is the listener's "readable", the bit the kernel reports for it.
            if ((ops & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0) {
                reading.add(fd);
            }
            if ((ops & SelectionKey.OP_WRITE) != 0) {
                writing.add(fd);
            }
            if ((ops & SelectionKey.OP_CONNECT) != 0) {
                // Java keeps a channel "pending" until someone finishes the connect, so the call the
                // reference makes on write readiness is made here first.
                SocketModule.settle(fd);
                writing.add(fd);
            }
        }
        selector.selectedKeys().clear();
        return new Ready(reading, writing);
    }

    private static PyObject answer(List<PyObject> readable, List<PyObject> writable) {
        return PyTuple.of(list(readable), list(writable), PyList.empty());
    }

    private static PyList list(List<PyObject> items) {
        return PyList.of(items.toArray(new PyObject[0]));
    }

    /**
     * The descriptors in one list that are ready now.
     *
     * <p>A socket's answer comes from the selector's own report; a {@link Fds} descriptor's from the
     * pipe it names.
     */
    private static List<PyObject> collect(
            List<Entry> entries, Set<Integer> readyFds, boolean reading) {
        List<PyObject> ready = new ArrayList<>();
        for (Entry entry : entries) {
            boolean isReady =
                    SocketModule.owns(entry.fd())
                            ? readyFds.contains(entry.fd())
                            : reading ? Fds.readable(entry.fd()) : Fds.writable(entry.fd());
            if (isReady) {
                ready.add(entry.object);
            }
        }
        return ready;
    }

    private static boolean hasPipes(List<Entry> entries) {
        for (Entry entry : entries) {
            if (!SocketModule.owns(entry.fd())) {
                return true;
            }
        }
        return false;
    }

    /** A number neither table owns is the {@code EBADF} the reference reports. */
    private static void checkDescriptors(List<Entry> reading, List<Entry> writing, List<Entry> exceptional) {
        for (List<Entry> group : List.of(reading, writing, exceptional)) {
            for (Entry entry : group) {
                if (!Fds.known(entry.fd()) && !SocketModule.owns(entry.fd())) {
                    throw badDescriptor();
                }
            }
        }
    }

    private static PyRaise badDescriptor() {
        return PyRaise.of(
                PyOSError.create(
                        ExceptionTypes.OS_ERROR,
                        new PyObject[] {PyInt.of(EBADF), PyStr.of("Bad file descriptor")}));
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
