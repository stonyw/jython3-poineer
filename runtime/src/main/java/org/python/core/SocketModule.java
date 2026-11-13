package org.python.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnixDomainSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code _socket}: the descriptor half of the socket module, over {@code java.nio.channels}.
 *
 * <p>The types and the module functions are written in Python beside this class as {@code _socket.py},
 * because {@code socket.py} subclasses {@code _socket.socket} and declares {@code __slots__} of its own;
 * a base class with a {@code __dict__} would refuse that declaration. Everything that has to reach the
 * operating system is an injected {@code _jy3_*} function implemented here.
 *
 * <p>A descriptor is a number this class hands out, mapped to a {@link Handle}. {@code dup} makes a
 * second number for the same handle and {@code detach} lets the ownership move to a later wrapper. The
 * two refusals this design brings are named in {@code PLAN.md}: the numbers are not the kernel's own,
 * so they do not participate in {@code select} or {@code os.get_inheritable}, and only
 * {@code SO_REUSEADDR} among the socket options changes what the operating system does — the rest are
 * remembered and read back, which is what keeps {@code has_dualstack_ipv6} and the tests that echo an
 * option working.
 *
 * <p>Unix domain sockets, including {@code socketpair}, use {@link StandardProtocolFamily#UNIX} and a
 * temporary path that is removed as soon as the pair is connected; the pair reports {@code ''} as its
 * address, as the reference does. Datagram socket pairs, raw sockets, {@code sendmsg}/{@code recvmsg}
 * and the host lookup calls outside the four below are not implemented, and raise rather than pretend.
 */
final class SocketModule {

    private static final String SOURCE = "/org/python/core/_socket.py";

    private static final int AF_UNSPEC = 0;
    private static final int AF_UNIX = 1;
    private static final int AF_INET = 2;
    private static final int AF_INET6 = 30;

    private static final int SOCK_STREAM = 1;
    private static final int SOCK_DGRAM = 2;

    private static final int SOL_SOCKET = 65535;
    private static final int SO_REUSEADDR = 4;
    private static final int SO_SNDBUF = 4097;
    private static final int SO_RCVBUF = 4098;
    private static final int SO_ERROR = 4103;
    private static final int SO_TYPE = 4104;
    private static final int SO_ACCEPTCONN = 2;

    private static final int SHUT_RD = 0;
    private static final int SHUT_WR = 1;

    private static final int AI_PASSIVE = 1;
    private static final int AI_CANONNAME = 2;
    private static final int AI_NUMERICHOST = 4;

    private static final int EIO = 5;
    private static final int EBADF = 9;
    private static final int EINVAL = 22;
    private static final int EPIPE = 32;
    private static final int EAGAIN = 35;
    private static final int EINPROGRESS = 36;
    private static final int EPROTONOSUPPORT = 43;
    private static final int EAFNOSUPPORT = 47;
    private static final int EADDRINUSE = 48;
    private static final int EADDRNOTAVAIL = 49;
    private static final int ECONNRESET = 54;
    private static final int ENOTCONN = 57;
    private static final int ECONNREFUSED = 61;
    private static final int EHOSTUNREACH = 65;
    private static final int EOPNOTSUPP = 102;

    private static final String NODENAME =
            "nodename nor servname provided, or not known";

    private static final Map<Integer, Handle> HANDLES = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger(100);

    private static PyModule module;

    private SocketModule() {
    }

    static synchronized PyModule install() {
        if (module != null) {
            return module;
        }
        if (!PyRuntime.hasSourceCompiler()) {
            throw Slots.notImplemented("_socket needs a compiler (setSourceCompiler)");
        }
        PyModule built = PyRuntime.namespace(new PyModule("_socket"));
        PyRuntime.markBuiltIn(built, "_socket");
        PyDict globals = built.dictionary();
        globals.put(PyStr.interned("error"), ExceptionTypes.OS_ERROR);
        globals.put(PyStr.interned("timeout"), ExceptionTypes.TIMEOUT_ERROR);
        globals.put(PyStr.interned("gaierror"), ExceptionTypes.GAI_ERROR);
        globals.put(PyStr.interned("herror"), ExceptionTypes.HER_ERROR);
        nativeFunction(globals, "_new", "new", SocketModule::newSocket);
        nativeFunction(globals, "_info", "socket.__init__", SocketModule::info);
        nativeFunction(globals, "_index", "index", SocketModule::index);
        nativeFunction(globals, "_as_double", "as_double", SocketModule::asDouble);
        nativeFunction(globals, "_close", "close", SocketModule::close);
        nativeFunction(globals, "_dup", "dup", SocketModule::dup);
        nativeFunction(globals, "_bind", "bind", SocketModule::bind);
        nativeFunction(globals, "_listen", "listen", SocketModule::listen);
        nativeFunction(globals, "_accept", "accept", SocketModule::accept);
        nativeFunction(globals, "_connect", "connect", SocketModule::connect);
        nativeFunction(globals, "_connect_ex", "connect_ex", SocketModule::connectEx);
        nativeFunction(globals, "_send", "send", SocketModule::send);
        nativeFunction(globals, "_sendall", "sendall", SocketModule::sendAll);
        nativeFunction(globals, "_sendto", "sendto", SocketModule::sendTo);
        nativeFunction(globals, "_recv", "recv", SocketModule::recv);
        nativeFunction(globals, "_recv_into", "recv_into", SocketModule::recvInto);
        nativeFunction(globals, "_recvfrom", "recvfrom", SocketModule::recvFrom);
        nativeFunction(globals, "_recvfrom_into", "recvfrom_into", SocketModule::recvFromInto);
        nativeFunction(globals, "_shutdown", "shutdown", SocketModule::shutdown);
        nativeFunction(globals, "_getsockname", "getsockname", SocketModule::getsockname);
        nativeFunction(globals, "_getpeername", "getpeername", SocketModule::getpeername);
        nativeFunction(globals, "_getsockopt", "getsockopt", SocketModule::getsockopt);
        nativeFunction(globals, "_setsockopt", "setsockopt", SocketModule::setsockopt);
        nativeFunction(globals, "_pair", "socketpair", SocketModule::pair);
        nativeFunction(globals, "_gethostname", "gethostname", SocketModule::hostName);
        nativeFunction(globals, "_gethostbyname", "gethostbyname", SocketModule::hostByName);
        nativeFunction(globals, "_getaddrinfo", "getaddrinfo", SocketModule::addressInfo);
        nativeFunction(globals, "_inet_aton", "inet_aton", SocketModule::aton);
        nativeFunction(globals, "_inet_ntoa", "inet_ntoa", SocketModule::ntoa);
        nativeFunction(globals, "_inet_pton", "inet_pton", SocketModule::pton);
        nativeFunction(globals, "_inet_ntop", "inet_ntop", SocketModule::ntop);
        nativeFunction(globals, "_htons", "htons", SocketModule::htons);
        nativeFunction(globals, "_htonl", "htonl", SocketModule::htonl);
        nativeFunction(globals, "_ntohs", "ntohs", SocketModule::ntohs);
        nativeFunction(globals, "_ntohl", "ntohl", SocketModule::ntohl);
        PyRuntime.runIn(PyRuntime.compileSource(source(), "<_socket>"), globals, globals);
        built.set("__name__", PyStr.interned("_socket"));
        PyRuntime.registerBuiltInModule("_socket", built);
        module = built;
        return built;
    }

    private static void nativeFunction(
            PyDict globals,
            String injected,
            String name,
            java.util.function.Function<PyObject[], PyObject> body) {
        globals.put(PyStr.interned("_jy3" + injected),
                PyNativeFunction.positional(name, body));
    }

    // --- descriptors -------------------------------------------------------------------------

    private static final class Handle {
        final int family;
        final int type;
        final int proto;
        final Map<Long, Integer> options = new HashMap<>();
        SocketChannel stream;
        ServerSocketChannel server;
        DatagramChannel datagram;
        boolean listening;
        boolean anonymous;
        String unixPath;
        int refs = 1;

        Handle(int family, int type, int proto) {
            this.family = family;
            this.type = type;
            this.proto = proto;
        }

        int option(int level, int option) {
            Integer value = options.get(key(level, option));
            return value == null ? 0 : value;
        }

        void option(int level, int option, int value) {
            options.put(key(level, option), value);
        }

        private static long key(int level, int option) {
            return ((long) level << 32) | (option & 0xFFFFFFFFL);
        }
    }

    private static Handle handle(int fd) {
        Handle found = HANDLES.get(fd);
        if (found == null) {
            throw badFileDescriptor();
        }
        return found;
    }

    private static int slot(PyObject value) {
        return Numbers.asIndex(value).intValue();
    }

    private static Double timeout(PyObject value) {
        if (value == PyNone.INSTANCE) {
            return null;
        }
        return timeoutValue(value);
    }

    private static double timeoutValue(PyObject value) {
        if (value instanceof PyFloat number) {
            return number.getValue();
        }
        if (value instanceof PyInt || value instanceof PyBool) {
            return Numbers.asInteger(value).doubleValue();
        }
        throw Slots.typeError(
                "'%s' object cannot be interpreted as an integer or float",
                Slots.argumentName(value));
    }

    private static PyObject index(PyObject[] args) {
        Arguments.exactlyOne("index", args, null);
        return PyInt.of(Numbers.asIndex(args[0]));
    }

    private static PyObject asDouble(PyObject[] args) {
        Arguments.exactlyOne("as_double", args, null);
        return PyFloat.of(timeoutValue(args[0]));
    }

    // --- construction ------------------------------------------------------------------------

    private static PyObject newSocket(PyObject[] args) {
        Arguments.exactly("new", args, null, 3);
        int family = slot(args[0]);
        int type = slot(args[1]);
        int proto = slot(args[2]);
        if (family != AF_INET && family != AF_INET6 && family != AF_UNIX) {
            throw osError(EAFNOSUPPORT, "Address family not supported by protocol family");
        }
        if (type != SOCK_STREAM && type != SOCK_DGRAM) {
            // Raw and sequenced-packet sockets need a platform API Java does not offer.
            throw osError(EPROTONOSUPPORT, "Protocol not supported");
        }
        if (family == AF_UNIX) {
            if (proto != 0) {
                throw osError(EPROTONOSUPPORT, "Protocol not supported");
            }
        } else {
            int wanted = type == SOCK_STREAM ? 6 : 17;
            if (proto != 0 && proto != wanted) {
                throw osError(EPROTONOSUPPORT, "Protocol not supported");
            }
        }
        return PyInt.of(register(new Handle(family, type, proto)));
    }

    private static int register(Handle handle) {
        int fd = NEXT.getAndIncrement();
        HANDLES.put(fd, handle);
        return fd;
    }

    private static PyObject info(PyObject[] args) {
        Arguments.exactly("socket.__init__", args, null, 4);
        Handle found = handle(slot(args[0]));
        return PyTuple.of(
                PyInt.of(found.family), PyInt.of(found.type), PyInt.of(found.proto));
    }

    private static PyObject close(PyObject[] args) {
        Arguments.exactly("close", args, null, 1);
        int fd = slot(args[0]);
        Handle found = HANDLES.remove(fd);
        if (found == null) {
            throw badFileDescriptor();
        }
        if (found.refs > 1) {
            found.refs--;
            return PyNone.INSTANCE;
        }
        quietly(found.server);
        quietly(found.stream);
        quietly(found.datagram);
        return PyNone.INSTANCE;
    }

    private static void quietly(AutoCloseable channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (Exception ignored) {
            // Closing what the operating system already dropped is not an error for Python.
        }
    }

    private static PyObject dup(PyObject[] args) {
        Arguments.exactly("dup", args, null, 1);
        Handle found = handle(slot(args[0]));
        found.refs++;
        return PyInt.of(register(found));
    }

    // --- binding and listening ---------------------------------------------------------------

    private static PyObject bind(PyObject[] args) {
        Arguments.exactly("bind", args, null, 2);
        bindHandle(handle(slot(args[0])), args[1]);
        return PyNone.INSTANCE;
    }

    private static void bindHandle(Handle handle, PyObject address) {
        try {
            if (handle.type == SOCK_STREAM) {
                if (handle.server != null) {
                    throw osError(EINVAL, "Invalid argument");
                }
                ServerSocketChannel server = ServerSocketChannel.open(protocol(handle.family));
                server.configureBlocking(false);
                if (handle.option(SOL_SOCKET, SO_REUSEADDR) != 0) {
                    server.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                }
                SocketAddress local = socketAddress(handle, address);
                if (local instanceof UnixDomainSocketAddress unix) {
                    handle.unixPath = unix.getPath().toString();
                }
                // The backlog belongs to listen() in Python; Java fixes it at bind time, so every
                // listener here carries SOMAXCONN and listen() only records the call.
                server.bind(local, 128);
                handle.server = server;
            } else {
                if (handle.datagram != null) {
                    throw osError(EINVAL, "Invalid argument");
                }
                DatagramChannel datagram = DatagramChannel.open(protocol(handle.family));
                datagram.configureBlocking(false);
                if (handle.option(SOL_SOCKET, SO_REUSEADDR) != 0) {
                    datagram.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                }
                datagram.bind(socketAddress(handle, address));
                handle.datagram = datagram;
            }
        } catch (IOException e) {
            throw fault(e);
        }
    }

    private static PyObject listen(PyObject[] args) {
        Arguments.exactly("listen", args, null, 2);
        Handle handle = handle(slot(args[0]));
        if (handle.type != SOCK_STREAM) {
            throw osError(EOPNOTSUPP, "Operation not supported on socket");
        }
        if (handle.server == null) {
            bindHandle(handle, defaultAddress(handle.family));
        }
        handle.listening = true;
        return PyNone.INSTANCE;
    }

    private static PyObject accept(PyObject[] args) {
        Arguments.exactly("accept", args, null, 2);
        Handle handle = handle(slot(args[0]));
        if (handle.server == null) {
            throw osError(EINVAL, "Invalid argument");
        }
        SocketChannel connected = acceptOne(handle.server, timeout(args[1]));
        try {
            connected.configureBlocking(false);
        } catch (IOException e) {
            throw fault(e);
        }
        Handle accepted = new Handle(handle.family, handle.type, handle.proto);
        accepted.stream = connected;
        int fd = register(accepted);
        PyObject address;
        try {
            address = addressOf(connected.getRemoteAddress(), handle.family);
        } catch (IOException e) {
            throw fault(e);
        }
        return PyTuple.of(PyInt.of(fd), address);
    }

    private static SocketChannel acceptOne(ServerSocketChannel server, Double limit) {
        try {
            if (limit == null) {
                server.configureBlocking(true);
                return server.accept();
            }
            server.configureBlocking(false);
            if (limit == 0.0) {
                SocketChannel connected = server.accept();
                if (connected == null) {
                    throw wouldBlock();
                }
                return connected;
            }
            if (!ready(server, SelectionKey.OP_ACCEPT, limit)) {
                throw timedOut();
            }
            SocketChannel connected = server.accept();
            if (connected == null) {
                throw wouldBlock();
            }
            return connected;
        } catch (IOException e) {
            throw fault(e);
        }
    }

    // --- connection --------------------------------------------------------------------------

    private static PyObject connect(PyObject[] args) {
        Arguments.exactly("connect", args, null, 3);
        connectHandle(handle(slot(args[0])), args[1], timeout(args[2]));
        return PyNone.INSTANCE;
    }

    private static PyObject connectEx(PyObject[] args) {
        Arguments.exactly("connect_ex", args, null, 3);
        try {
            connectHandle(handle(slot(args[0])), args[1], timeout(args[2]));
            return PyInt.of(0);
        } catch (PyRaise raised) {
            PyObject errno = raised.value().getAttr("errno");
            if (errno == PyNone.INSTANCE) {
                throw raised;
            }
            return PyInt.of(Numbers.asIndex(errno).intValue());
        }
    }

    private static void connectHandle(Handle handle, PyObject address, Double limit) {
        try {
            if (handle.type == SOCK_DGRAM) {
                datagram(handle).connect(socketAddress(handle, address));
                return;
            }
            SocketAddress local = null;
            if (handle.server != null) {
                local = handle.server.getLocalAddress();
                handle.server.close();
                handle.server = null;
            }
            SocketChannel channel = stream(handle);
            if (local != null) {
                channel.bind(local);
            }
            connectOne(channel, socketAddress(handle, address), limit);
        } catch (IOException e) {
            throw fault(e);
        }
    }

    private static void connectOne(SocketChannel channel, SocketAddress address, Double limit) {
        try {
            if (limit == null) {
                channel.configureBlocking(true);
                channel.connect(address);
                channel.configureBlocking(false);
                return;
            }
            channel.configureBlocking(false);
            if (channel.connect(address)) {
                return;
            }
            if (limit == 0.0) {
                throw osError(EINPROGRESS, "Operation now in progress");
            }
            if (!readyConnected(channel, limit)) {
                throw timedOut();
            }
        } catch (IOException e) {
            throw fault(e);
        }
    }

    private static boolean readyConnected(SocketChannel channel, double limit) {
        try (Selector selector = Selector.open()) {
            channel.register(selector, SelectionKey.OP_CONNECT);
            long deadline = System.nanoTime() + (long) (limit * 1_000_000_000L);
            while (true) {
                long remain = deadline - System.nanoTime();
                if (remain <= 0) {
                    return false;
                }
                if (selector.select(Math.max(1, remain / 1_000_000L)) > 0) {
                    selector.selectedKeys().clear();
                    return channel.finishConnect();
                }
            }
        } catch (IOException e) {
            throw fault(e);
        }
    }

    // --- sending and receiving ---------------------------------------------------------------

    private static PyObject send(PyObject[] args) {
        Arguments.exactly("send", args, null, 4);
        Handle handle = handle(slot(args[0]));
        byte[] octets = PyFileIO.octetsToWrite(args[1]);
        Double limit = timeout(args[3]);
        if (handle.type == SOCK_DGRAM) {
            if (handle.datagram == null) {
                throw osError(ENOTCONN, "Socket is not connected");
            }
            return PyInt.of(writeDatagram(handle, octets, limit));
        }
        return PyInt.of(writeStream(stream(handle), octets, limit));
    }

    private static PyObject sendAll(PyObject[] args) {
        Arguments.exactly("sendall", args, null, 4);
        Handle handle = handle(slot(args[0]));
        byte[] octets = PyFileIO.octetsToWrite(args[1]);
        Double limit = timeout(args[3]);
        if (handle.type == SOCK_DGRAM) {
            writeDatagram(handle, octets, limit);
            return PyNone.INSTANCE;
        }
        SocketChannel channel = stream(handle);
        ByteBuffer buffer = ByteBuffer.wrap(octets);
        while (buffer.hasRemaining()) {
            int written = writeStream(channel, buffer, limit);
            if (written <= 0) {
                throw osError(EPIPE, "Broken pipe");
            }
        }
        return PyNone.INSTANCE;
    }

    private static PyObject sendTo(PyObject[] args) {
        Arguments.exactly("sendto", args, null, 5);
        Handle handle = handle(slot(args[0]));
        byte[] octets = PyFileIO.octetsToWrite(args[1]);
        Double limit = timeout(args[4]);
        if (handle.type != SOCK_DGRAM) {
            return PyInt.of(writeStream(stream(handle), octets, limit));
        }
        DatagramChannel datagram = datagram(handle);
        try {
            return PyInt.of(
                    datagram.send(ByteBuffer.wrap(octets), socketAddress(handle, args[3])));
        } catch (IOException e) {
            throw fault(e);
        }
    }

    private static int writeDatagram(Handle handle, byte[] octets, Double limit) {
        DatagramChannel datagram = datagram(handle);
        SocketAddress target;
        try {
            target = datagram.getRemoteAddress();
        } catch (IOException e) {
            throw fault(e);
        }
        if (target == null) {
            throw osError(ENOTCONN, "Socket is not connected");
        }
        try {
            return datagram.send(ByteBuffer.wrap(octets), target);
        } catch (IOException e) {
            throw fault(e);
        }
    }

    private static PyObject recv(PyObject[] args) {
        Arguments.exactly("recv", args, null, 4);
        Handle handle = handle(slot(args[0]));
        int size = slot(args[1]);
        Double limit = timeout(args[3]);
        if (handle.type == SOCK_DGRAM) {
            ByteBuffer buffer = ByteBuffer.allocate(size);
            receiveDatagram(handle, buffer, limit);
            return PyBytes.of(taken(buffer));
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        int count = readStream(stream(handle), buffer, limit);
        if (count < 0) {
            return PyBytes.of(new byte[0]);
        }
        return PyBytes.of(taken(buffer));
    }

    private static PyObject recvInto(PyObject[] args) {
        Arguments.exactly("recv_into", args, null, 5);
        Handle handle = handle(slot(args[0]));
        PyMemoryView view = PyMemoryView.of(args[1]);
        int asked = slot(args[2]);
        int size = asked <= 0 ? view.byteLength() : Math.min(asked, view.byteLength());
        Double limit = timeout(args[4]);
        ByteBuffer buffer = ByteBuffer.allocate(size);
        int count;
        if (handle.type == SOCK_DGRAM) {
            receiveDatagram(handle, buffer, limit);
            count = buffer.position();
        } else {
            count = readStream(stream(handle), buffer, limit);
            if (count < 0) {
                count = 0;
            }
        }
        view.writeWindow(0, buffer.array(), 0, count);
        return PyInt.of(count);
    }

    private static PyObject recvFrom(PyObject[] args) {
        Arguments.exactly("recvfrom", args, null, 4);
        Handle handle = handle(slot(args[0]));
        int size = slot(args[1]);
        Double limit = timeout(args[3]);
        ByteBuffer buffer = ByteBuffer.allocate(size);
        if (handle.type == SOCK_DGRAM) {
            SocketAddress from = receiveDatagram(handle, buffer, limit);
            return PyTuple.of(PyBytes.of(taken(buffer)), addressOf(from, handle.family));
        }
        int count = readStream(stream(handle), buffer, limit);
        if (count < 0) {
            count = 0;
        }
        return PyTuple.of(
                PyBytes.of(prefix(buffer, count)), peerAddress(handle));
    }

    private static PyObject recvFromInto(PyObject[] args) {
        Arguments.exactly("recvfrom_into", args, null, 5);
        Handle handle = handle(slot(args[0]));
        PyMemoryView view = PyMemoryView.of(args[1]);
        int asked = slot(args[2]);
        int size = asked <= 0 ? view.byteLength() : Math.min(asked, view.byteLength());
        Double limit = timeout(args[4]);
        ByteBuffer buffer = ByteBuffer.allocate(size);
        SocketAddress from;
        int count;
        if (handle.type == SOCK_DGRAM) {
            from = receiveDatagram(handle, buffer, limit);
            count = buffer.position();
        } else {
            count = readStream(stream(handle), buffer, limit);
            if (count < 0) {
                count = 0;
            }
            from = null;
        }
        view.writeWindow(0, buffer.array(), 0, count);
        PyObject address = from == null ? peerAddress(handle) : addressOf(from, handle.family);
        return PyTuple.of(PyInt.of(count), address);
    }

    private static SocketAddress receiveDatagram(Handle handle, ByteBuffer buffer, Double limit) {
        DatagramChannel datagram = datagram(handle);
        try {
            if (limit == null) {
                datagram.configureBlocking(true);
                return datagram.receive(buffer);
            }
            datagram.configureBlocking(false);
            if (limit == 0.0) {
                if (datagram.receive(buffer) == null) {
                    throw wouldBlock();
                }
            } else {
                if (!ready(datagram, SelectionKey.OP_READ, limit)) {
                    throw timedOut();
                }
                if (datagram.receive(buffer) == null) {
                    throw wouldBlock();
                }
            }
            return datagram.receive(buffer);
        } catch (IOException e) {
            throw fault(e);
        }
    }

    private static byte[] taken(ByteBuffer buffer) {
        return prefix(buffer, buffer.position());
    }

    private static byte[] prefix(ByteBuffer buffer, int count) {
        byte[] out = new byte[count];
        System.arraycopy(buffer.array(), 0, out, 0, count);
        return out;
    }

    private static int readStream(SocketChannel channel, ByteBuffer buffer, Double limit) {
        try {
            if (limit == null) {
                channel.configureBlocking(true);
                return channel.read(buffer);
            }
            channel.configureBlocking(false);
            if (limit == 0.0) {
                if (!ready(channel, SelectionKey.OP_READ, 0.0)) {
                    throw wouldBlock();
                }
            } else if (!ready(channel, SelectionKey.OP_READ, limit)) {
                throw timedOut();
            }
            int count = channel.read(buffer);
            if (count == 0) {
                throw wouldBlock();
            }
            return count;
        } catch (IOException e) {
            throw fault(e);
        }
    }

    private static int writeStream(SocketChannel channel, byte[] octets, Double limit) {
        return writeStream(channel, ByteBuffer.wrap(octets), limit);
    }

    private static int writeStream(SocketChannel channel, ByteBuffer buffer, Double limit) {
        try {
            if (limit == null) {
                channel.configureBlocking(true);
                int count = channel.write(buffer);
                if (count == 0 && buffer.hasRemaining()) {
                    count = channel.write(buffer);
                }
                return count;
            }
            channel.configureBlocking(false);
            if (limit == 0.0) {
                if (!ready(channel, SelectionKey.OP_WRITE, 0.0)) {
                    throw wouldBlock();
                }
            } else if (!ready(channel, SelectionKey.OP_WRITE, limit)) {
                throw timedOut();
            }
            int count = channel.write(buffer);
            if (count == 0) {
                throw wouldBlock();
            }
            return count;
        } catch (IOException e) {
            throw fault(e);
        }
    }

    /** Whether the channel can do {@code op} within {@code limit} seconds; zero asks once. */
    private static boolean ready(SelectableChannel channel, int op, double limit) {
        try (Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            channel.register(selector, op);
            if (limit == 0.0) {
                return selector.selectNow() > 0;
            }
            long deadline = System.nanoTime() + (long) (limit * 1_000_000_000L);
            while (true) {
                long remain = deadline - System.nanoTime();
                if (remain <= 0) {
                    return false;
                }
                if (selector.select(Math.max(1, remain / 1_000_000L)) > 0) {
                    selector.selectedKeys().clear();
                    return true;
                }
            }
        } catch (IOException e) {
            throw fault(e);
        }
    }

    private static PyObject shutdown(PyObject[] args) {
        Arguments.exactly("shutdown", args, null, 2);
        Handle handle = handle(slot(args[0]));
        int how = slot(args[1]);
        if (how != SHUT_RD && how != SHUT_WR && how != 2) {
            throw osError(EINVAL, "Invalid argument");
        }
        if (handle.stream == null) {
            throw osError(ENOTCONN, "Socket is not connected");
        }
        try {
            if (how != SHUT_RD) {
                handle.stream.shutdownOutput();
            }
            if (how != SHUT_WR) {
                handle.stream.shutdownInput();
            }
        } catch (IOException e) {
            throw fault(e);
        }
        return PyNone.INSTANCE;
    }

    // --- names and options -------------------------------------------------------------------

    private static PyObject getsockname(PyObject[] args) {
        Arguments.exactly("getsockname", args, null, 1);
        Handle handle = handle(slot(args[0]));
        if (handle.anonymous) {
            return PyStr.of("");
        }
        SocketAddress local = null;
        try {
            if (handle.server != null) {
                local = handle.server.getLocalAddress();
            } else if (handle.stream != null) {
                local = handle.stream.getLocalAddress();
            } else if (handle.datagram != null) {
                local = handle.datagram.getLocalAddress();
            }
        } catch (IOException e) {
            throw fault(e);
        }
        if (local == null) {
            return defaultAddress(handle.family);
        }
        return addressOf(local, handle.family);
    }

    private static PyObject getpeername(PyObject[] args) {
        Arguments.exactly("getpeername", args, null, 1);
        return peerAddress(handle(slot(args[0])));
    }

    private static PyObject peerAddress(Handle handle) {
        if (handle.anonymous) {
            return PyStr.of("");
        }
        SocketAddress remote = null;
        try {
            if (handle.stream != null) {
                remote = handle.stream.getRemoteAddress();
            } else if (handle.datagram != null) {
                remote = handle.datagram.getRemoteAddress();
            }
        } catch (IOException e) {
            throw fault(e);
        }
        if (remote == null) {
            throw osError(ENOTCONN, "Socket is not connected");
        }
        return addressOf(remote, handle.family);
    }

    private static PyObject getsockopt(PyObject[] args) {
        Arguments.between("getsockopt", args, null, 3, 4);
        Handle handle = handle(slot(args[0]));
        int level = slot(args[1]);
        int option = slot(args[2]);
        int value;
        if (level == SOL_SOCKET && option == SO_TYPE) {
            value = handle.type;
        } else if (level == SOL_SOCKET && option == SO_ERROR) {
            value = 0;
        } else if (level == SOL_SOCKET && option == SO_ACCEPTCONN) {
            value = handle.listening ? 1 : 0;
        } else if (level == SOL_SOCKET && (option == SO_SNDBUF || option == SO_RCVBUF)) {
            value = handle.option(level, option);
            if (value == 0) {
                value = 131072;
            }
        } else {
            value = handle.option(level, option);
        }
        if (args.length > 3 && args[3] != PyNone.INSTANCE) {
            int size = Math.max(4, slot(args[3]));
            byte[] packed = new byte[size];
            packed[0] = (byte) value;
            packed[1] = (byte) (value >>> 8);
            packed[2] = (byte) (value >>> 16);
            packed[3] = (byte) (value >>> 24);
            return PyBytes.of(packed);
        }
        return PyInt.of(value);
    }

    private static PyObject setsockopt(PyObject[] args) {
        Arguments.between("setsockopt", args, null, 4, 5);
        Handle handle = handle(slot(args[0]));
        int level = slot(args[1]);
        int option = slot(args[2]);
        PyObject value = args[3];
        if (value instanceof PyBytes octets) {
            byte[] packed = octets.getValue();
            int number = 0;
            for (int i = Math.min(3, packed.length - 1); i >= 0; i--) {
                number = (number << 8) | (packed[i] & 0xFF);
            }
            handle.option(level, option, number);
        } else {
            handle.option(level, option, slot(value));
        }
        return PyNone.INSTANCE;
    }

    // --- socket pairs ------------------------------------------------------------------------

    private static PyObject pair(PyObject[] args) {
        Arguments.exactly("socketpair", args, null, 3);
        int family = slot(args[0]);
        int type = slot(args[1]);
        int proto = slot(args[2]);
        if (type != SOCK_STREAM) {
            throw osError(EOPNOTSUPP, "Operation not supported on socket");
        }
        try {
            SocketChannel[] ends;
            boolean anonymous;
            if (family == AF_UNIX) {
                ends = unixPair();
                anonymous = true;
            } else if (family == AF_INET) {
                ends = tcpPair(StandardProtocolFamily.INET, "127.0.0.1");
                anonymous = false;
            } else if (family == AF_INET6) {
                ends = tcpPair(StandardProtocolFamily.INET6, "::1");
                anonymous = false;
            } else {
                throw osError(EAFNOSUPPORT, "Address family not supported by protocol family");
            }
            Handle first = new Handle(family, type, proto);
            first.stream = ends[0];
            first.anonymous = anonymous;
            Handle second = new Handle(family, type, proto);
            second.stream = ends[1];
            second.anonymous = anonymous;
            return PyTuple.of(PyInt.of(register(first)), PyInt.of(register(second)));
        } catch (IOException e) {
            throw fault(e);
        }
    }

    private static SocketChannel[] tcpPair(StandardProtocolFamily protocol, String loopback)
            throws IOException {
        InetAddress address = InetAddress.getByName(loopback);
        ServerSocketChannel server = ServerSocketChannel.open(protocol);
        SocketChannel first = null;
        try {
            server.bind(new InetSocketAddress(address, 0), 1);
            int port = ((InetSocketAddress) server.getLocalAddress()).getPort();
            first = SocketChannel.open(protocol);
            first.connect(new InetSocketAddress(address, port));
            SocketChannel second = server.accept();
            first.configureBlocking(false);
            second.configureBlocking(false);
            return new SocketChannel[] {first, second};
        } finally {
            quietly(server);
        }
    }

    private static SocketChannel[] unixPair() throws IOException {
        Path directory = Files.createTempDirectory("jy3sock");
        Path path = directory.resolve("s");
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        SocketChannel first = null;
        try {
            server.bind(UnixDomainSocketAddress.of(path));
            first = SocketChannel.open(StandardProtocolFamily.UNIX);
            first.connect(UnixDomainSocketAddress.of(path));
            SocketChannel second = server.accept();
            first.configureBlocking(false);
            second.configureBlocking(false);
            return new SocketChannel[] {first, second};
        } finally {
            quietly(server);
            // The pair is anonymous: the path is only how the two ends met.
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        }
    }

    // --- host names and addresses ------------------------------------------------------------

    private static PyObject hostName(PyObject[] args) {
        Arguments.none("gethostname", args, null);
        try {
            return PyStr.of(InetAddress.getLocalHost().getHostName());
        } catch (UnknownHostException e) {
            return PyStr.of("localhost");
        }
    }

    private static PyObject hostByName(PyObject[] args) {
        Arguments.exactlyOne("gethostbyname", args, null);
        String name = text(args[0]);
        try {
            return PyStr.of(InetAddress.getByName(name).getHostAddress());
        } catch (UnknownHostException e) {
            throw gaiError(8, NODENAME);
        }
    }

    private static PyObject addressInfo(PyObject[] args) {
        Arguments.exactly("getaddrinfo", args, null, 6);
        String host = args[0] == PyNone.INSTANCE ? null : text(args[0]);
        if (host != null && host.isEmpty()) {
            host = null;
        }
        int port = portNumber(args[1]);
        int family = slot(args[2]);
        int type = slot(args[3]);
        int proto = slot(args[4]);
        int flags = slot(args[5]);
        boolean numbered = (flags & AI_NUMERICHOST) != 0;

        List<InetAddress> resolved = new ArrayList<>();
        if (host == null) {
            if (family == AF_INET || family == AF_UNSPEC) {
                resolved.add(numeric("0.0.0.0"));
            }
            if (family == AF_INET6 || family == AF_UNSPEC) {
                resolved.add(numeric("::"));
            }
            if ((flags & AI_PASSIVE) == 0 && family == AF_UNSPEC) {
                resolved.clear();
                resolved.add(numeric("0.0.0.0"));
            }
        } else if (numbered) {
            InetAddress one = numeric(host);
            if (one == null) {
                throw gaiError(8, NODENAME);
            }
            resolved.add(one);
        } else {
            try {
                for (InetAddress one : InetAddress.getAllByName(host)) {
                    resolved.add(one);
                }
            } catch (UnknownHostException e) {
                throw gaiError(8, NODENAME);
            }
        }

        List<InetAddress> kept = new ArrayList<>();
        for (InetAddress one : resolved) {
            if (family == AF_UNSPEC
                    || (family == AF_INET && one instanceof Inet4Address)
                    || (family == AF_INET6 && one instanceof Inet6Address)) {
                kept.add(one);
            }
        }
        if (kept.isEmpty()) {
            throw gaiError(8, NODENAME);
        }

        PyList answers = PyList.empty();
        boolean canon = (flags & AI_CANONNAME) != 0;
        for (int i = 0; i < kept.size(); i++) {
            InetAddress one = kept.get(i);
            int actual = one instanceof Inet6Address ? AF_INET6 : AF_INET;
            String name = canon && i == 0 ? one.getCanonicalHostName() : "";
            if (type == 0) {
                answers.add(result(actual, SOCK_DGRAM, proto == 0 ? 17 : proto, name, one, port));
                answers.add(result(actual, SOCK_STREAM, proto == 0 ? 6 : proto, name, one, port));
            } else {
                int number = proto != 0 ? proto : (type == SOCK_STREAM ? 6 : 17);
                answers.add(result(actual, type, number, name, one, port));
            }
        }
        return answers;
    }

    private static PyObject result(
            int family, int type, int proto, String canon, InetAddress address, int port) {
        return PyTuple.of(
                PyInt.of(family),
                PyInt.of(type),
                PyInt.of(proto),
                PyStr.of(canon),
                sockaddrOf(family, address, port));
    }

    private static int portNumber(PyObject value) {
        if (value == PyNone.INSTANCE) {
            return 0;
        }
        if (value instanceof PyStr text) {
            String digits = text.getValue();
            if (digits.isEmpty()) {
                return 0;
            }
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                throw gaiError(9, "Servname not supported for ai_socktype");
            }
        }
        return slot(value);
    }

    private static InetAddress numeric(String host) {
        try {
            if (host.indexOf(':') >= 0) {
                return InetAddress.getByName(host);
            }
            byte[] packed = parseV4(host);
            if (packed == null) {
                return null;
            }
            return InetAddress.getByAddress(packed);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static PyObject aton(PyObject[] args) {
        Arguments.exactlyOne("inet_aton", args, null);
        byte[] packed = parseV4(text(args[0]));
        if (packed == null) {
            throw osError("illegal IP address string passed to inet_aton");
        }
        return PyBytes.of(packed);
    }

    private static PyObject ntoa(PyObject[] args) {
        Arguments.exactlyOne("inet_ntoa", args, null);
        byte[] packed = PyFileIO.octetsToWrite(args[0]);
        if (packed.length != 4) {
            throw osError("packed IP wrong length for inet_ntoa");
        }
        return PyStr.of(showV4(packed));
    }

    private static PyObject pton(PyObject[] args) {
        Arguments.exactly("inet_pton", args, null, 2);
        int family = slot(args[0]);
        String host = text(args[1]);
        if (family == AF_INET) {
            byte[] packed = parseV4(host);
            if (packed == null) {
                throw osError("illegal IP address string passed to inet_pton");
            }
            return PyBytes.of(packed);
        }
        if (family == AF_INET6) {
            if (host.indexOf(':') < 0) {
                throw osError("illegal IP address string passed to inet_pton");
            }
            try {
                InetAddress parsed = InetAddress.getByName(host);
                if (!(parsed instanceof Inet6Address)) {
                    throw osError("illegal IP address string passed to inet_pton");
                }
                return PyBytes.of(parsed.getAddress());
            } catch (UnknownHostException e) {
                throw osError("illegal IP address string passed to inet_pton");
            }
        }
        throw osError(EAFNOSUPPORT, "Address family not supported by protocol family");
    }

    private static PyObject ntop(PyObject[] args) {
        Arguments.exactly("inet_ntop", args, null, 2);
        int family = slot(args[0]);
        byte[] packed = PyFileIO.octetsToWrite(args[1]);
        if (family == AF_INET) {
            if (packed.length != 4) {
                throw Slots.valueError("invalid length of packed IP address string");
            }
            return PyStr.of(showV4(packed));
        }
        if (family == AF_INET6) {
            if (packed.length != 16) {
                throw Slots.valueError("invalid length of packed IP address string");
            }
            return PyStr.of(showV6(packed));
        }
        throw Slots.valueError("unknown address family %d", family);
    }

    private static byte[] parseV4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] packed = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            for (int at = 0; at < part.length(); at++) {
                if (part.charAt(at) < '0' || part.charAt(at) > '9') {
                    return null;
                }
            }
            int value = Integer.parseInt(part);
            if (value > 255) {
                return null;
            }
            packed[i] = (byte) value;
        }
        return packed;
    }

    private static String showV4(byte[] packed) {
        return (packed[0] & 0xFF) + "." + (packed[1] & 0xFF) + "." + (packed[2] & 0xFF) + "."
                + (packed[3] & 0xFF);
    }

    private static String showV6(byte[] packed) {
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((packed[i * 2] & 0xFF) << 8) | (packed[i * 2 + 1] & 0xFF);
        }
        int bestStart = -1;
        int bestRun = 0;
        for (int i = 0; i < 8; ) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int end = i;
            while (end < 8 && groups[end] == 0) {
                end++;
            }
            if (end - i > bestRun) {
                bestRun = end - i;
                bestStart = i;
            }
            i = end;
        }
        StringBuilder out = new StringBuilder();
        if (bestRun < 2) {
            for (int i = 0; i < 8; i++) {
                if (i > 0) {
                    out.append(':');
                }
                out.append(Integer.toHexString(groups[i]));
            }
            return out.toString();
        }
        for (int i = 0; i < bestStart; i++) {
            if (i > 0) {
                out.append(':');
            }
            out.append(Integer.toHexString(groups[i]));
        }
        out.append("::");
        for (int i = bestStart + bestRun; i < 8; i++) {
            if (i > bestStart + bestRun) {
                out.append(':');
            }
            out.append(Integer.toHexString(groups[i]));
        }
        return out.toString();
    }

    private static PyObject htons(PyObject[] args) {
        Arguments.exactlyOne("htons", args, null);
        long value = unsigned(args[0], 0xFFFF, "uint16_t", "htons");
        return PyInt.of((int) (((value & 0xFF) << 8) | ((value >>> 8) & 0xFF)));
    }

    private static PyObject ntohs(PyObject[] args) {
        Arguments.exactlyOne("ntohs", args, null);
        long value = unsigned(args[0], 0xFFFF, "uint16_t", "ntohs");
        return PyInt.of((int) (((value & 0xFF) << 8) | ((value >>> 8) & 0xFF)));
    }

    private static PyObject htonl(PyObject[] args) {
        Arguments.exactlyOne("htonl", args, null);
        long value = unsigned(args[0], 0xFFFFFFFFL, "uint32_t", "htonl");
        long swapped = ((value & 0xFF) << 24)
                | ((value & 0xFF00) << 8)
                | ((value >>> 8) & 0xFF00)
                | ((value >>> 24) & 0xFF);
        return PyInt.of(swapped);
    }

    private static PyObject ntohl(PyObject[] args) {
        Arguments.exactlyOne("ntohl", args, null);
        long value = unsigned(args[0], 0xFFFFFFFFL, "uint32_t", "ntohl");
        long swapped = ((value & 0xFF) << 24)
                | ((value & 0xFF00) << 8)
                | ((value >>> 8) & 0xFF00)
                | ((value >>> 24) & 0xFF);
        return PyInt.of(swapped);
    }

    private static long unsigned(PyObject value, long limit, String cType, String name) {
        java.math.BigInteger number = Numbers.asIndex(value);
        if (number.signum() < 0) {
            throw Slots.valueError("Cannot convert negative int");
        }
        if (number.bitLength() > 32 || number.longValue() > limit) {
            throw Slots.overflowError("Python int too large for C %s", cType);
        }
        return number.longValue();
    }

    // --- addresses ---------------------------------------------------------------------------

    private static PyObject addressOf(SocketAddress address, int family) {
        if (address instanceof UnixDomainSocketAddress unix) {
            return PyStr.of(unix.getPath().toString());
        }
        InetSocketAddress inet = (InetSocketAddress) address;
        return sockaddrOf(family, inet.getAddress(), inet.getPort());
    }

    private static PyObject sockaddrOf(int family, InetAddress address, int port) {
        String host = address == null ? "" : address.getHostAddress();
        if (family == AF_INET6) {
            return PyTuple.of(
                    PyStr.of(address instanceof Inet6Address ? showV6(address.getAddress()) : host),
                    PyInt.of(port),
                    PyInt.of(0),
                    PyInt.of(0));
        }
        return PyTuple.of(PyStr.of(host), PyInt.of(port));
    }

    private static PyObject defaultAddress(int family) {
        if (family == AF_UNIX) {
            return PyStr.of("");
        }
        if (family == AF_INET6) {
            return PyTuple.of(PyStr.of("::"), PyInt.of(0), PyInt.of(0), PyInt.of(0));
        }
        return PyTuple.of(PyStr.of("0.0.0.0"), PyInt.of(0));
    }

    private static SocketAddress socketAddress(Handle handle, PyObject address) {
        if (handle.family == AF_UNIX) {
            String path = text(address);
            return UnixDomainSocketAddress.of(path);
        }
        PyTuple tuple = (PyTuple) address;
        String host = text(tuple.at(0));
        int port = slot(tuple.at(1));
        String fallback = handle.family == AF_INET6 ? "::" : "0.0.0.0";
        try {
            InetAddress resolved = host.isEmpty()
                    ? InetAddress.getByName(fallback)
                    : InetAddress.getByName(host);
            return new InetSocketAddress(resolved, port);
        } catch (UnknownHostException e) {
            throw gaiError(8, NODENAME);
        }
    }

    private static String text(PyObject value) {
        if (value instanceof PyStr string) {
            return string.getValue();
        }
        if (value instanceof PyBytes octets) {
            return new String(octets.getValue(), StandardCharsets.UTF_8);
        }
        return value.str();
    }

    private static StandardProtocolFamily protocol(int family) {
        return switch (family) {
            case AF_INET6 -> StandardProtocolFamily.INET6;
            case AF_UNIX -> StandardProtocolFamily.UNIX;
            default -> StandardProtocolFamily.INET;
        };
    }

    private static SocketChannel stream(Handle handle) {
        if (handle.stream == null) {
            try {
                handle.stream = SocketChannel.open(protocol(handle.family));
                handle.stream.configureBlocking(false);
            } catch (IOException e) {
                throw fault(e);
            }
        }
        return handle.stream;
    }

    private static DatagramChannel datagram(Handle handle) {
        if (handle.datagram == null) {
            try {
                handle.datagram = DatagramChannel.open(protocol(handle.family));
                handle.datagram.configureBlocking(false);
            } catch (IOException e) {
                throw fault(e);
            }
        }
        return handle.datagram;
    }

    // --- errors ------------------------------------------------------------------------------

    private static PyRaise fault(IOException e) {
        if (e instanceof ClosedChannelException) {
            return badFileDescriptor();
        }
        if (e instanceof ConnectException) {
            return osError(ECONNREFUSED, "Connection refused");
        }
        if (e instanceof BindException) {
            String message = String.valueOf(e.getMessage());
            return message.contains("in use")
                    ? osError(EADDRINUSE, "Address already in use")
                    : osError(EADDRNOTAVAIL, "Can't assign requested address");
        }
        if (e instanceof NoRouteToHostException) {
            return osError(EHOSTUNREACH, "No route to host");
        }
        if (e instanceof SocketTimeoutException) {
            return timedOut();
        }
        String message = String.valueOf(e.getMessage());
        if (message.contains("Broken pipe")) {
            return osError(EPIPE, "Broken pipe");
        }
        if (message.contains("Connection reset")) {
            return osError(ECONNRESET, "Connection reset by peer");
        }
        return osError(EIO, "Input/output error");
    }

    private static PyRaise osError(int errno, String message) {
        return PyRaise.of(
                PyOSError.create(
                        ExceptionTypes.OS_ERROR,
                        new PyObject[] {PyInt.of(errno), PyStr.of(message)}));
    }

    private static PyRaise osError(String message) {
        return PyRaise.of(
                PyOSError.create(ExceptionTypes.OS_ERROR, new PyObject[] {PyStr.of(message)}));
    }

    private static PyRaise gaiError(int errno, String message) {
        return PyRaise.of(
                PyOSError.create(
                        ExceptionTypes.GAI_ERROR,
                        new PyObject[] {PyInt.of(errno), PyStr.of(message)}));
    }

    private static PyRaise badFileDescriptor() {
        return osError(EBADF, "Bad file descriptor");
    }

    private static PyRaise wouldBlock() {
        return osError(EAGAIN, "Resource temporarily unavailable");
    }

    private static PyRaise timedOut() {
        return PyRaise.of(
                PyOSError.create(
                        ExceptionTypes.TIMEOUT_ERROR, new PyObject[] {PyStr.of("timed out")}));
    }

    private static String source() {
        try (InputStream stream = SocketModule.class.getResourceAsStream(SOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("the runtime is missing " + SOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + SOURCE, e);
        }
    }
}
