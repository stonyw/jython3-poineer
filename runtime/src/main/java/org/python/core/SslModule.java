package org.python.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * {@code _ssl}: TLS over the JVM's JSSE, in place of OpenSSL.
 *
 * <p>The Python half lives beside this class as {@code _ssl.py}: the constants, the exception
 * classes, {@code MemoryBIO}, {@code _SSLContext}, {@code _SSLSocket}. Everything that has to
 * reach the TLS implementation is injected here as a {@code _jy3_*} function.
 *
 * <p>This runtime is not OpenSSL, and the version constants say so. The API-shaped numbers
 * ({@code CERT_*}, {@code OP_*}, protocol versions) keep the values the Python API documents
 * because programs compare against them; {@code OPENSSL_VERSION*} reports the truth instead of
 * inventing a release the process is not running.
 *
 * <p>What JSSE can do: a real TLS 1.2/1.3 handshake, certificate chains and verification,
 * hostname checking, ALPN, SNI, cipher selection within the suites the JDK offers. What it
 * cannot do, and this class refuses rather than simulates: OpenSSL cipher strings outside the
 * mapped subset, PSK, NPN, TLS-level compression, tls-unique channel binding, per-session
 * ticket statistics, and the memory-BIO {@code SSLObject} path (the engine plumbing is not
 * wired yet, so {@code _wrap_bio} raises {@code NotImplementedError}).
 */
final class SslModule {

    private static final String SOURCE = "/org/python/core/_ssl.py";

    private static final char[] KEY_PASSWORD = "changeit".toCharArray();

    private static final AtomicInteger NEXT = new AtomicInteger(1);

    private static final Map<Integer, Ctx> CONTEXTS = new ConcurrentHashMap<>();
    private static final Map<Integer, Ssl> OBJECTS = new ConcurrentHashMap<>();

    private static PyModule module;

    private SslModule() {
    }

    static synchronized PyModule install() {
        if (module != null) {
            return module;
        }
        if (!PyRuntime.hasSourceCompiler()) {
            throw Slots.notImplemented("_ssl needs a compiler (setSourceCompiler)");
        }
        PyModule built = PyRuntime.namespace(new PyModule("_ssl"));
        PyRuntime.markBuiltIn(built, "_ssl");
        PyDict globals = built.dictionary();
        nativeFunction(globals, "_ctx_new", "_SSLContext", SslModule::ctxNew);
        nativeFunction(globals, "_ctx_get", "_SSLContext", SslModule::ctxGet);
        nativeFunction(globals, "_ctx_set", "_SSLContext", SslModule::ctxSet);
        nativeFunction(globals, "_ctx_load_cert_chain", "_SSLContext.load_cert_chain",
                SslModule::ctxLoadCertChain);
        nativeFunction(globals, "_ctx_load_verify_locations", "_SSLContext.load_verify_locations",
                SslModule::ctxLoadVerifyLocations);
        nativeFunction(globals, "_ctx_set_default_verify_paths", "_SSLContext.set_default_verify_paths",
                SslModule::ctxSetDefaultVerifyPaths);
        nativeFunction(globals, "_ctx_set_ciphers", "_SSLContext.set_ciphers",
                SslModule::ctxSetCiphers);
        nativeFunction(globals, "_ctx_set_ecdh_curve", "_SSLContext.set_ecdh_curve",
                SslModule::ctxSetEcdhCurve);
        nativeFunction(globals, "_ctx_set_alpn_protocols", "_SSLContext._set_alpn_protocols",
                SslModule::ctxSetAlpnProtocols);
        nativeFunction(globals, "_ctx_get_ca_certs", "_SSLContext.get_ca_certs",
                SslModule::ctxGetCaCerts);
        nativeFunction(globals, "_ctx_get_ciphers", "_SSLContext.get_ciphers",
                SslModule::ctxGetCiphers);
        nativeFunction(globals, "_ctx_cert_store_stats", "_SSLContext.cert_store_stats",
                SslModule::ctxCertStoreStats);
        nativeFunction(globals, "_ctx_session_stats", "_SSLContext.session_stats",
                SslModule::ctxSessionStats);
        nativeFunction(globals, "_ctx_load_dh_params", "_SSLContext.load_dh_params",
                SslModule::ctxLoadDhParams);
        nativeFunction(globals, "_ctx_wrap_socket", "_SSLContext._wrap_socket",
                SslModule::ctxWrapSocket);

        nativeFunction(globals, "_ssl_read", "_SSLSocket.read", SslModule::sslRead);
        nativeFunction(globals, "_ssl_read_into", "_SSLSocket.read", SslModule::sslReadInto);
        nativeFunction(globals, "_ssl_write", "_SSLSocket.write", SslModule::sslWrite);
        nativeFunction(globals, "_ssl_do_handshake", "_SSLSocket.do_handshake",
                SslModule::sslDoHandshake);
        nativeFunction(globals, "_ssl_set_timeout", "_SSLSocket._set_timeout",
                SslModule::sslSetTimeout);
        nativeFunction(globals, "_ssl_getpeercert", "_SSLSocket.getpeercert",
                SslModule::sslGetPeerCert);
        nativeFunction(globals, "_ssl_get_chain", "_SSLSocket.get_verified_chain",
                SslModule::sslGetChain);
        nativeFunction(globals, "_ssl_alpn", "_SSLSocket.selected_alpn_protocol",
                SslModule::sslAlpn);
        nativeFunction(globals, "_ssl_cipher", "_SSLSocket.cipher", SslModule::sslCipher);
        nativeFunction(globals, "_ssl_shared_ciphers", "_SSLSocket.shared_ciphers",
                SslModule::sslSharedCiphers);
        nativeFunction(globals, "_ssl_pending", "_SSLSocket.pending", SslModule::sslPending);
        nativeFunction(globals, "_ssl_shutdown", "_SSLSocket.shutdown", SslModule::sslShutdown);
        nativeFunction(globals, "_ssl_version", "_SSLSocket.version", SslModule::sslVersion);
        nativeFunction(globals, "_ssl_channel_binding", "_SSLSocket.get_channel_binding",
                SslModule::sslChannelBinding);
        nativeFunction(globals, "_ssl_session", "_SSLSocket.session", SslModule::sslSession);
        nativeFunction(globals, "_ssl_set_session", "_SSLSocket.session", SslModule::sslSetSession);
        nativeFunction(globals, "_ssl_session_reused", "_SSLSocket.session_reused",
                SslModule::sslSessionReused);
        nativeFunction(globals, "_ssl_set_context", "_SSLSocket.context", SslModule::sslSetContext);

        nativeFunction(globals, "_rand_bytes", "RAND_bytes", SslModule::randBytes);
        nativeFunction(globals, "_default_verify_paths", "get_default_verify_paths",
                SslModule::defaultVerifyPaths);
        nativeFunction(globals, "_octets", "memoryview", SslModule::octets);
        nativeFunction(globals, "_pem_encode", "PEM", SslModule::pemEncode);
        nativeFunction(globals, "_cert_info", "certificate", SslModule::certInfo);
        nativeFunction(globals, "_decode_cert", "_test_decode_cert", SslModule::decodeCert);

        PyRuntime.runIn(PyRuntime.compileSource(source(), "<_ssl>"), globals, globals);
        built.set("__name__", PyStr.interned("_ssl"));
        PyRuntime.registerBuiltInModule("_ssl", built);
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

    // --- context state -----------------------------------------------------------------------

    private static final class Ctx {
        int protocol;
        int verifyMode;
        boolean checkHostname;
        int minimumVersion = 771;
        int maximumVersion = -1;
        /** {@code options} is an unsigned long in the reference; a long is the closest Java has. */
        long options;
        int verifyFlags = 32768;
        int hostFlags;
        boolean postHandshakeAuth;
        int numTickets = 2;
        int securityLevel = 2;
        PyObject keylogFilename = PyNone.INSTANCE;
        PyObject sniCallback = PyNone.INSTANCE;
        PyObject msgCallback = PyNone.INSTANCE;
        byte[] alpn;
        String ecdhCurve;
        String cipherSpec;
        List<String> cipherSuites = List.of();

        final List<X509Certificate> chain = new ArrayList<>();
        PrivateKey privateKey;
        final List<X509Certificate> caCerts = new ArrayList<>();
        boolean defaultCerts;
    }

    private static final class Ssl {
        /**
         * The JSSE socket, made on first use.
         *
         * <p>It cannot be made when the owner is not connected yet: both
         * {@code SSLSocketFactory.createSocket} overloads that wrap an existing socket require a
         * connected one, while the reference lets {@code wrap_socket()} return a socket that
         * connects later ({@code s = ctx.wrap_socket(sock); s.connect(addr)}). So the wrap only
         * records how to build it, and the first handshake, read or write builds it.
         */
        private SSLSocket wrapped;

        final SocketChannel channel;
        final PyObject owner;
        final boolean serverSide;
        int timeoutMillis;
        /** The owner's timeout was 0: the reference reports want-read rather than a timeout. */
        boolean nonBlocking;
        final String hostname;
        int ctxId;
        boolean closed;

        Ssl(SocketChannel channel, PyObject owner, boolean serverSide, int timeoutMillis,
                boolean nonBlocking, String hostname, int ctxId) {
            this.channel = channel;
            this.owner = owner;
            this.serverSide = serverSide;
            this.timeoutMillis = timeoutMillis;
            this.nonBlocking = nonBlocking;
            this.hostname = hostname;
            this.ctxId = ctxId;
        }

        SSLSocket socket() {
            if (wrapped == null) {
                wrapped = open(this);
            }
            return wrapped;
        }
    }

    /**
     * Runs one JSSE operation with the channel in blocking mode.
     *
     * <p>{@code SSLSocket} refuses to read or write over a non-blocking channel, while this
     * runtime keeps every channel non-blocking between calls so that {@code select()} can
     * register it. Switching for the duration of one call gives both: JSSE sees the blocking
     * channel it requires, and the descriptor is non-blocking again when the call returns. The
     * Python-level timeout still applies through {@code SO_TIMEOUT}.
     */
    @FunctionalInterface
    private interface IoAction<T> {
        T run() throws Exception;
    }

    private static <T> T blocking(Ssl ssl, IoAction<T> action) {
        boolean restore = !ssl.channel.isBlocking();
        if (restore) {
            try {
                ssl.channel.configureBlocking(true);
            } catch (IOException failure) {
                throw translate(failure, ssl.timeoutMillis, ssl.nonBlocking, true, false);
            }
        }
        try {
            return action.run();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw translate(failure, ssl.timeoutMillis, ssl.nonBlocking, true, false);
        } finally {
            if (restore) {
                try {
                    ssl.channel.configureBlocking(false);
                } catch (IOException ignored) {
                    // Closing the socket is what the caller asked for; the mode no longer matters.
                }
            }
        }
    }

    /** Build the JSSE socket for a wrapped Python socket, connected by now or failing for it. */
    private static SSLSocket open(Ssl ssl) {
        try {
            Socket plain = ssl.channel.socket();
            try {
                plain.setSoTimeout(ssl.timeoutMillis);
            } catch (Exception ignored) {
                // A socket that refuses SO_TIMEOUT still works; it just cannot be interrupted.
            }
            Ctx ctx = context(PyInt.of(ssl.ctxId));
            SSLContext jsse = build(ctx);
            SSLSocket socket;
            if (ssl.serverSide) {
                socket = (SSLSocket) jsse.getSocketFactory().createSocket(
                        plain, new ByteArrayInputStream(new byte[0]), false);
            } else {
                String peerHost = ssl.hostname != null ? ssl.hostname
                        : plain.getInetAddress().getHostAddress();
                socket = (SSLSocket) jsse.getSocketFactory().createSocket(
                        plain, peerHost, plain.getPort(), false);
            }
            applyParameters(ctx, socket, ssl.serverSide, ssl.hostname);
            return socket;
        } catch (Exception failure) {
            throw translate(failure, ssl.timeoutMillis, ssl.nonBlocking, true, false);
        }
    }

    private static Ctx context(PyObject value) {
        int id = Numbers.asIndex(value).intValue();
        Ctx found = CONTEXTS.get(id);
        if (found == null) {
            throw Slots.valueError("invalid _ssl context");
        }
        return found;
    }

    private static Ssl object(PyObject value) {
        int id = Numbers.asIndex(value).intValue();
        Ssl found = OBJECTS.get(id);
        if (found == null) {
            throw Slots.valueError("invalid _ssl object");
        }
        return found;
    }

    // --- context construction ----------------------------------------------------------------

    private static PyObject ctxNew(PyObject[] args) {
        Arguments.exactlyOne("_SSLContext", args, null);
        int protocol = Numbers.asIndex(args[0]).intValue();
        if (protocol != 2 && protocol != 3 && protocol != 4 && protocol != 5
                && protocol != 16 && protocol != 17) {
            throw Slots.valueError("invalid protocol version");
        }
        Ctx ctx = new Ctx();
        ctx.protocol = protocol;
        if (protocol == 16) {
            ctx.verifyMode = 2;
            ctx.checkHostname = true;
            ctx.hostFlags = 4;
        } else {
            ctx.verifyMode = 0;
        }
        // OP_ALL | OP_NO_SSLv2 | OP_NO_SSLv3, plus the four options ssl.py says a context enables.
        ctx.options = 2147483712L | 33554432L | 131072L | 4194304L | 1048576L;
        int id = NEXT.getAndIncrement();
        CONTEXTS.put(id, ctx);
        return PyInt.of(id);
    }

    private static PyObject ctxGet(PyObject[] args) {
        Arguments.exactly("_SSLContext.__get__", args, null, 2);
        Ctx ctx = context(args[0]);
        String field = ((PyStr) Arguments.text(1, args[1])).getValue();
        switch (field) {
            case "protocol":
                return PyInt.of(ctx.protocol);
            case "verify_mode":
                return PyInt.of(ctx.verifyMode);
            case "check_hostname":
                return PyBool.of(ctx.checkHostname);
            case "minimum_version":
                return PyInt.of(ctx.minimumVersion);
            case "maximum_version":
                return PyInt.of(ctx.maximumVersion);
            case "options":
                return PyInt.of(ctx.options);
            case "verify_flags":
                return PyInt.of(ctx.verifyFlags);
            case "host_flags":
                return PyInt.of(ctx.hostFlags);
            case "post_handshake_auth":
                return PyBool.of(ctx.postHandshakeAuth);
            case "num_tickets":
                return PyInt.of(ctx.numTickets);
            case "security_level":
                return PyInt.of(ctx.securityLevel);
            case "keylog_filename":
                return ctx.keylogFilename;
            case "sni_callback":
                return ctx.sniCallback;
            case "msg_callback":
                return ctx.msgCallback;
            default:
                throw Slots.valueError("unknown _ssl context field '" + field + "'");
        }
    }

    private static PyObject ctxSet(PyObject[] args) {
        Arguments.exactly("_SSLContext.__set__", args, null, 3);
        Ctx ctx = context(args[0]);
        String field = ((PyStr) Arguments.text(1, args[1])).getValue();
        PyObject value = args[2];
        switch (field) {
            case "verify_mode":
                ctx.verifyMode = Numbers.asIndex(value).intValue();
                break;
            case "check_hostname":
                ctx.checkHostname = Slots.truth(value);
                break;
            case "minimum_version":
                ctx.minimumVersion = tlsVersion(value);
                break;
            case "maximum_version":
                ctx.maximumVersion = tlsVersion(value);
                break;
            case "options":
                ctx.options = unsigned(value);
                break;
            case "verify_flags":
                ctx.verifyFlags = Numbers.asIndex(value).intValue();
                break;
            case "host_flags":
                ctx.hostFlags = Numbers.asIndex(value).intValue();
                break;
            case "post_handshake_auth":
                ctx.postHandshakeAuth = Slots.truth(value);
                break;
            case "num_tickets":
                if (ctx.protocol == 16) {   // PROTOCOL_TLS_CLIENT
                    throw Slots.valueError("SSLContext is not a server context.");
                }
                if (Numbers.asIndex(value).signum() < 0) {
                    throw Slots.valueError("value must be non-negative");
                }
                ctx.numTickets = Numbers.asIndex(value).intValue();
                break;
            case "security_level":
                ctx.securityLevel = Numbers.asIndex(value).intValue();
                break;
            case "keylog_filename":
                ctx.keylogFilename = value;
                break;
            case "sni_callback":
                ctx.sniCallback = value;
                break;
            case "msg_callback":
                ctx.msgCallback = value;
                break;
            default:
                throw Slots.valueError("unknown _ssl context field '" + field + "'");
        }
        return PyNone.INSTANCE;
    }

    /**
     * A {@code TLSVersion} a context can be bounded by: TLS 1.0 through 1.3, or "no bound".
     *
     * <p>SSLv3 has a version number and no implementation, so it gets the reference's own sentence
     * for it; anything else is not a protocol version at all.
     */
    private static int tlsVersion(PyObject value) {
        BigInteger number = Numbers.asIndex(value);
        if (number.bitLength() > 31) {
            throw Slots.valueError("Unsupported TLS/SSL version 0x%s", number.toString(16));
        }
        int version = number.intValue();
        if (version == -2 || version == -1 || version == 0x301 || version == 0x302
                || version == 0x303 || version == 0x304) {
            return version;
        }
        if (version == 0x300) {
            throw Slots.valueError("Unsupported protocol version 0x300");
        }
        throw Slots.valueError("Unsupported TLS/SSL version 0x%s", Integer.toHexString(version));
    }

    /** An {@code unsigned long} member: negative is a conversion error, oversized an overflow. */
    private static long unsigned(PyObject value) {
        BigInteger number = Numbers.asIndex(value);
        if (number.signum() < 0) {
            throw Slots.valueError("Cannot convert negative int");
        }
        if (number.bitLength() > 63) {
            throw Slots.overflowError("Python int too large for C unsigned long long");
        }
        return number.longValue();
    }

    // --- certificate and key loading ---------------------------------------------------------

    private static PyObject ctxLoadCertChain(PyObject[] args) {
        Arguments.exactly("_SSLContext.load_cert_chain", args, null, 4);
        Ctx ctx = context(args[0]);
        if (args[1] instanceof PyNone) {
            throw Slots.valueError("certfile must be specified");
        }
        String certfile = text(args[1]);
        String keyfile = args[2] instanceof PyNone ? certfile : text(args[2]);
        byte[] certBytes = readFile(certfile);
        List<X509Certificate> chain = parseCertificates(certBytes, certfile);
        if (chain.isEmpty()) {
            throw raiseNamed("SSLError", "SSL", "PEM_LIB",
                    PyInt.of(1), PyStr.of("[SSL] PEM lib"));
        }
        byte[] keyBytes = readFile(keyfile);
        PrivateKey key;
        try {
            key = parsePrivateKey(keyBytes, args[3]);
        } catch (UnsupportedOperationException unsupported) {
            throw Slots.notImplemented(unsupported.getMessage());
        } catch (Exception failure) {
            // OpenSSL reports every unreadable or undecryptable key as "PEM lib"; the reason is
            // in the error stack, which `str()` of the exception does not show.
            throw raiseNamed("SSLError", "SSL", "PEM_LIB",
                    PyInt.of(1), PyStr.of("[SSL] PEM lib"));
        }
        checkKeyMatches(chain.get(0), key);
        ctx.chain.clear();
        ctx.chain.addAll(chain);
        ctx.privateKey = key;
        return PyNone.INSTANCE;
    }

    /**
     * Refuses a private key that does not belong to the certificate, as OpenSSL does.
     *
     * <p>The check signs a fixed challenge with the key and verifies it with the certificate's
     * public key, which answers "same pair?" for every algorithm Java can sign with rather than
     * only comparing the RSA modulus.
     */
    private static void checkKeyMatches(X509Certificate cert, PrivateKey key) {
        String algorithm = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "DSA" -> "SHA256withDSA";
            case "Ed25519" -> "Ed25519";
            case "Ed448" -> "Ed448";
            default -> null;
        };
        if (algorithm == null) {
            return;
        }
        boolean matches;
        try {
            byte[] challenge = "jy3 tls key check".getBytes(StandardCharsets.US_ASCII);
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(challenge);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(cert.getPublicKey());
            verifier.update(challenge);
            matches = verifier.verify(signature);
        } catch (GeneralSecurityException | RuntimeException failure) {
            matches = false;
        }
        if (!matches) {
            throw raiseNamed("SSLError", "SSL", "KEY_VALUES_MISMATCH",
                    PyInt.of(1), PyStr.of("[SSL] key values mismatch"));
        }
    }

    private static PyObject ctxLoadVerifyLocations(PyObject[] args) {
        Arguments.exactly("_SSLContext.load_verify_locations", args, null, 4);
        Ctx ctx = context(args[0]);
        List<X509Certificate> added = new ArrayList<>();
        boolean any = false;
        if (!(args[1] instanceof PyNone)) {
            String cafile = text(args[1]);
            added.addAll(parseCertificates(readFile(cafile), cafile));
            any = true;
        }
        if (!(args[2] instanceof PyNone)) {
            String capath = text(args[2]);
            Path dir = Path.of(capath);
            if (!Files.isDirectory(dir)) {
                throw fileNotFound(capath);
            }
            try (var entries = Files.list(dir)) {
                for (Path entry : entries.sorted().toList()) {
                    if (!Files.isRegularFile(entry)) {
                        continue;
                    }
                    try {
                        added.addAll(parseCertificates(Files.readAllBytes(entry),
                                entry.toString()));
                    } catch (IOException | RuntimeException ignored) {
                        // A directory of trust anchors may hold non-certificate files.
                    }
                }
            } catch (IOException failure) {
                throw raiseNamed("SSLError", PyInt.of(1), PyStr.of(failure.getMessage()));
            }
            any = true;
        }
        boolean cadataText = false;
        boolean cadataGiven = false;
        if (!(args[3] instanceof PyNone)) {
            cadataText = args[3] instanceof PyStr;
            cadataGiven = true;
            added.addAll(parseCaData(args[3]));
            any = true;
        }
        if (!any) {
            throw Slots.valueError("cafile, capath and cadata cannot be all omitted");
        }
        if (added.isEmpty()) {
            // OpenSSL says which decoding step failed: text without a header is "no start
            // line", binary too short to hold a certificate is "not enough data".
            if (cadataGiven && added.isEmpty() && !hasFileOrDir(args)) {
                String reason = cadataText ? "no start line" : "not enough data";
                throw raiseNamed("SSLError", "PEM",
                        cadataText ? "NO_START_LINE" : "BAD_DATA",
                        PyInt.of(1),
                        PyStr.of("[SSL] " + reason
                                + ": cadata does not contain a certificate"));
            }
            throw raiseNamed("SSLError", PyInt.of(1), PyStr.of("no certificate found"));
        }
        ctx.caCerts.addAll(added);
        return PyNone.INSTANCE;
    }

    private static boolean hasFileOrDir(PyObject[] args) {
        return !(args[1] instanceof PyNone) || !(args[2] instanceof PyNone);
    }

    private static List<X509Certificate> parseCaData(PyObject cadata) {
        if (cadata instanceof PyStr) {
            String text = ((PyStr) cadata).getValue();
            return parseCertificates(text.getBytes(StandardCharsets.US_ASCII), "cadata");
        }
        byte[] bytes = PyMemoryView.bufferOctets(cadata);
        List<X509Certificate> certs = parseCertificates(bytes, "cadata");
        if (certs.isEmpty()) {
            try {
                CertificateFactory factory = CertificateFactory.getInstance("X.509");
                certs.add((X509Certificate) factory.generateCertificate(
                        new ByteArrayInputStream(bytes)));
            } catch (Exception ignored) {
                // Reported by the caller as "no certificate found".
            }
        }
        return certs;
    }

    private static PyObject ctxSetDefaultVerifyPaths(PyObject[] args) {
        Arguments.exactlyOne("_SSLContext.set_default_verify_paths", args, null);
        Ctx ctx = context(args[0]);
        ctx.defaultCerts = true;
        // The reference's default paths are the ones OpenSSL was compiled with, overridden by
        // SSL_CERT_FILE and SSL_CERT_DIR. The JVM's equivalent of the compiled-in default is
        // its trust store; the two environment variables keep their OpenSSL meaning.
        String file = System.getenv("SSL_CERT_FILE");
        String dir = System.getenv("SSL_CERT_DIR");
        if (file == null && dir == null) {
            ctx.caCerts.clear();
            ctx.caCerts.addAll(jvmTrustAnchors());
            return PyNone.INSTANCE;
        }
        if (file != null && !file.isEmpty() && Files.isRegularFile(Path.of(file))) {
            ctx.caCerts.addAll(parseCertificates(readFile(file), file));
        }
        if (dir != null && !dir.isEmpty() && Files.isDirectory(Path.of(dir))) {
            try (var entries = Files.list(Path.of(dir))) {
                for (Path entry : entries.sorted().toList()) {
                    if (!Files.isRegularFile(entry)) {
                        continue;
                    }
                    try {
                        ctx.caCerts.addAll(parseCertificates(Files.readAllBytes(entry),
                                entry.toString()));
                    } catch (IOException | RuntimeException ignored) {
                        // A directory of trust anchors may hold files that are not certificates.
                    }
                }
            } catch (IOException ignored) {
                // OpenSSL's default store loads lazily; an unreadable directory is empty.
            }
        }
        return PyNone.INSTANCE;
    }

    private static List<X509Certificate> jvmTrustAnchors() {
        List<X509Certificate> out = new ArrayList<>();
        Path cacerts = Path.of(System.getProperty("java.home"), "lib", "security", "cacerts");
        if (!Files.isRegularFile(cacerts)) {
            return out;
        }
        try (InputStream in = Files.newInputStream(cacerts)) {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(in, KEY_PASSWORD);
            for (String alias : java.util.Collections.list(store.aliases())) {
                java.security.cert.Certificate cert = store.getCertificate(alias);
                if (cert instanceof X509Certificate x509) {
                    out.add(x509);
                }
            }
        } catch (Exception ignored) {
            // An unreadable trust store is reported by get_ca_certs as an empty list.
        }
        return out;
    }

    private static PyObject ctxSetCiphers(PyObject[] args) {
        Arguments.exactly("_SSLContext.set_ciphers", args, null, 2);
        Ctx ctx = context(args[0]);
        String spec = text(args[1]);
        List<String> suites = mapCipherSpec(spec);
        if (suites == null) {
            throw raiseNamed("SSLError", PyStr.of("No cipher can be selected."));
        }
        ctx.cipherSpec = spec;
        ctx.cipherSuites = suites;
        return PyNone.INSTANCE;
    }

    private static PyObject ctxSetEcdhCurve(PyObject[] args) {
        Arguments.exactly("_SSLContext.set_ecdh_curve", args, null, 2);
        Ctx ctx = context(args[0]);
        String name = text(args[1]);
        String group = curveGroup(name);
        if (group == null) {
            throw Slots.valueError("unknown elliptic curve name '" + name + "'");
        }
        ctx.ecdhCurve = group;
        return PyNone.INSTANCE;
    }

    private static PyObject ctxSetAlpnProtocols(PyObject[] args) {
        Arguments.exactly("_SSLContext._set_alpn_protocols", args, null, 2);
        Ctx ctx = context(args[0]);
        ctx.alpn = PyMemoryView.bufferOctets(args[1]);
        return PyNone.INSTANCE;
    }

    private static PyObject ctxLoadDhParams(PyObject[] args) {
        Arguments.exactly("_SSLContext.load_dh_params", args, null, 2);
        context(args[0]);
        String path = text(args[1]);
        byte[] data = readFile(path);
        String content = new String(data, StandardCharsets.US_ASCII);
        if (!content.contains("BEGIN DH PARAMETERS")
                && !content.contains("BEGIN X9.42 DH PARAMETERS")) {
            // The same refusal OpenSSL makes for a file whose first block is not a parameter set.
            throw raiseNamed("SSLError", "PEM", "NO_START_LINE",
                    PyInt.of(108), PyStr.of("[PEM: NO_START_LINE] no start line"));
        }
        // JSSE has no API for a caller-supplied DH parameter set: it picks its own FFDHE groups.
        // Accepting the file and ignoring it would be a lie, so the limit is stated.
        throw Slots.notImplemented(
                "load_dh_params: the JVM TLS stack chooses its own DHE groups and cannot be given"
                        + " a parameter file");
    }

    private static PyObject ctxGetCaCerts(PyObject[] args) {
        Arguments.exactly("_SSLContext.get_ca_certs", args, null, 2);
        Ctx ctx = context(args[0]);
        boolean binary = Slots.truth(args[1]);
        PyList out = PyList.of();
        for (X509Certificate cert : ctx.caCerts) {
            try {
                out.add(binary ? PyBytes.of(cert.getEncoded()) : certDict(cert, true));
            } catch (Exception failure) {
                throw raiseNamed("SSLError", PyInt.of(1), PyStr.of(failure.getMessage()));
            }
        }
        return out;
    }

    private static PyObject ctxGetCiphers(PyObject[] args) {
        Arguments.exactlyOne("_SSLContext.get_ciphers", args, null);
        Ctx ctx = context(args[0]);
        PyList out = PyList.of();
        int id = 0;
        for (String suite : ctx.cipherSuites.isEmpty() ? supportedSuites() : ctx.cipherSuites) {
            PyDict entry = PyDict.empty();
            entry.put(PyStr.interned("id"), PyInt.of(id++));
            entry.put(PyStr.interned("name"), PyStr.of(opensslCipherName(suite)));
            entry.put(PyStr.interned("protocol"), PyStr.of(cipherProtocol(suite)));
            entry.put(PyStr.interned("description"), PyStr.of(suite));
            entry.put(PyStr.interned("strength_bits"), PyInt.of(cipherBits(suite)));
            entry.put(PyStr.interned("alg_bits"), PyInt.of(cipherBits(suite)));
            entry.put(PyStr.interned("aead"), PyBool.TRUE);
            entry.put(PyStr.interned("symmetric"), PyStr.of(suite));
            entry.put(PyStr.interned("digest"), PyNone.INSTANCE);
            entry.put(PyStr.interned("kea"), PyStr.of("kx-any"));
            entry.put(PyStr.interned("auth"), PyStr.of("auth-any"));
            out.add(entry);
        }
        return out;
    }

    private static PyObject ctxCertStoreStats(PyObject[] args) {
        Arguments.exactlyOne("_SSLContext.cert_store_stats", args, null);
        Ctx ctx = context(args[0]);
        PyDict out = PyDict.empty();
        int ca = 0;
        for (X509Certificate cert : ctx.caCerts) {
            // A CA certificate is the one BasicConstraints marks as able to sign others.
            if (cert.getBasicConstraints() >= 0) {
                ca++;
            }
        }
        out.put(PyStr.interned("x509"), PyInt.of(ctx.caCerts.size()));
        out.put(PyStr.interned("crl"), PyInt.of(0));
        out.put(PyStr.interned("x509_ca"), PyInt.of(ca));
        return out;
    }

    private static PyObject ctxSessionStats(PyObject[] args) {
        Arguments.exactlyOne("_SSLContext.session_stats", args, null);
        context(args[0]);
        PyDict out = PyDict.empty();
        for (String name : List.of("number", "connect", "connect_good", "connect_renegotiate",
                "accept", "accept_good", "accept_renegotiate", "hits", "misses", "timeouts",
                "cache_full")) {
            out.put(PyStr.interned(name), PyInt.of(0));
        }
        return out;
    }

    // --- wrapping a socket -------------------------------------------------------------------

    private static PyObject ctxWrapSocket(PyObject[] args) {
        Arguments.exactly("_SSLContext._wrap_socket", args, null, 4);
        Ctx ctx = context(args[0]);
        PyObject owner = args[1];
        boolean serverSide = Slots.truth(args[2]);
        // A TLS client/server context names the role; wrapping it the other way is an error
        // before any bytes are exchanged.
        if (serverSide && ctx.protocol == 16) {
            throw raiseNamed("SSLError", PyInt.of(1), PyStr.of(
                    "Cannot create a server socket with a PROTOCOL_TLS_CLIENT context"));
        }
        if (!serverSide && ctx.protocol == 17) {
            throw raiseNamed("SSLError", PyInt.of(1), PyStr.of(
                    "Cannot create a client socket with a PROTOCOL_TLS_SERVER context"));
        }
        String hostname = args[3] instanceof PyNone ? null : text(args[3]);
        int fd = Numbers.asIndex(Slots.call(Slots.getAttr(owner, "fileno"),
                new PyObject[0], null)).intValue();
        SelectableChannel raw = SocketModule.channelFor(fd);
        if (!(raw instanceof SocketChannel channel)) {
            throw Slots.notImplemented(
                    "_ssl needs a connected stream socket; this descriptor has no stream channel");
        }
        double timeout = ownerTimeout(owner);
        int millis = timeoutMillis(timeout);
        // The JSSE socket is not built here: the owner may not be connected yet. `Ssl.socket()`
        // builds it on the first handshake, read or write, which is after `connect()` in that case;
        // each such call switches the channel to blocking for the duration of the JSSE work.
        int id = NEXT.getAndIncrement();
        OBJECTS.put(id, new Ssl(channel, owner, serverSide, millis, timeout == 0.0, hostname,
                ctxId(args[0])));
        return PyInt.of(id);
    }

    private static int ctxId(PyObject id) {
        int value = Numbers.asIndex(id).intValue();
        CONTEXTS.get(value); // validate
        return value;
    }

    private static void applyParameters(Ctx ctx, SSLSocket socket, boolean serverSide,
            String hostname) throws Exception {
        SSLParameters params = socket.getSSLParameters();
        params.setProtocols(protocols(ctx));
        if (ctx.cipherSuites != null && !ctx.cipherSuites.isEmpty()) {
            params.setCipherSuites(ctx.cipherSuites.toArray(new String[0]));
        }
        if (hostname != null && !serverSide) {
            params.setServerNames(List.of(new SNIHostName(hostname)));
        }
        if (ctx.alpn != null && ctx.alpn.length > 0) {
            params.setApplicationProtocols(decodeAlpn(ctx.alpn));
        }
        if (!serverSide && ctx.checkHostname) {
            params.setEndpointIdentificationAlgorithm("HTTPS");
        }
        if (serverSide) {
            if (ctx.verifyMode == 2) {
                params.setNeedClientAuth(true);
            } else if (ctx.verifyMode == 1) {
                params.setWantClientAuth(true);
            }
        }
        if (ctx.ecdhCurve != null) {
            params.setNamedGroups(new String[] {ctx.ecdhCurve});
        }
        socket.setSSLParameters(params);
    }

    private static SSLContext build(Ctx ctx) throws Exception {
        SSLContext jsse = SSLContext.getInstance("TLS");
        KeyManager[] keyManagers = null;
        if (ctx.privateKey != null) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            java.security.cert.Certificate[] chain =
                    ctx.chain.toArray(new java.security.cert.Certificate[0]);
            store.setKeyEntry("key", ctx.privateKey, KEY_PASSWORD, chain);
            KeyManagerFactory factory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(store, KEY_PASSWORD);
            keyManagers = factory.getKeyManagers();
        }
        TrustManager[] trustManagers = null;
        if (ctx.verifyMode == 0) {
            trustManagers = new TrustManager[] {new TrustAll()};
        } else if (!ctx.caCerts.isEmpty()) {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int index = 0;
            for (X509Certificate cert : ctx.caCerts) {
                store.setCertificateEntry("ca" + index++, cert);
            }
            TrustManagerFactory factory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            trustManagers = factory.getTrustManagers();
        }
        jsse.init(keyManagers, trustManagers, new SecureRandom());
        return jsse;
    }

    private static String[] protocols(Ctx ctx) {
        List<String> out = new ArrayList<>();
        int low = ctx.minimumVersion;
        int high = ctx.maximumVersion == -1 ? 772 : ctx.maximumVersion;
        for (int version = low; version <= high; version++) {
            String name = protocolName(version);
            if (name != null) {
                out.add(name);
            }
        }
        return out.toArray(new String[0]);
    }

    private static String protocolName(int version) {
        switch (version) {
            case 769:
                return "TLSv1";
            case 770:
                return "TLSv1.1";
            case 771:
                return "TLSv1.2";
            case 772:
                return "TLSv1.3";
            default:
                return null;
        }
    }

    private static String[] decodeAlpn(byte[] wire) {
        List<String> out = new ArrayList<>();
        int at = 0;
        while (at < wire.length) {
            int length = wire[at++] & 0xff;
            if (at + length > wire.length) {
                break;
            }
            out.add(new String(wire, at, length, StandardCharsets.US_ASCII));
            at += length;
        }
        return out.toArray(new String[0]);
    }

    private static double ownerTimeout(PyObject owner) {
        try {
            PyObject value = Slots.call(Slots.getAttr(owner, "gettimeout"), new PyObject[0], null);
            if (value instanceof PyNone) {
                return -1.0;
            }
            return Numeric.asDouble(value);
        } catch (RuntimeException ignored) {
            return -1.0;
        }
    }

    private static int timeoutMillis(double timeout) {
        if (timeout < 0) {
            return 0;
        }
        if (timeout == 0) {
            return 1;
        }
        long millis = Math.round(timeout * 1000.0);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, millis));
    }

    // --- TLS object operations ---------------------------------------------------------------

    /**
     * Records the owner's current Python timeout before one operation.
     *
     * <p>{@code SSLSocket.settimeout()} after {@code wrap_socket()} writes the timeout on the
     * Python socket object, which the JSSE socket does not see; the wrapper calls this so the
     * next operation uses the value the program set.
     */
    private static PyObject sslSetTimeout(PyObject[] args) {
        Arguments.exactly("_SSLSocket._set_timeout", args, null, 2);
        Ssl ssl = object(args[0]);
        double timeout = args[1] instanceof PyNone ? -1.0 : Numeric.asDouble(args[1]);
        ssl.timeoutMillis = timeoutMillis(timeout);
        ssl.nonBlocking = timeout == 0.0;
        return PyNone.INSTANCE;
    }

    private static PyObject sslDoHandshake(PyObject[] args) {
        Arguments.exactlyOne("_SSLSocket.do_handshake", args, null);
        Ssl ssl = object(args[0]);
        try {
            blocking(ssl, () -> {
                ssl.socket().setSoTimeout(ssl.timeoutMillis);
                Gil.blockingIO(() -> {
                    ssl.socket().startHandshake();
                    return null;
                });
                return null;
            });
        } catch (RuntimeException failure) {
            throw translate(failure, ssl.timeoutMillis, ssl.nonBlocking, true, false);
        }
        return PyNone.INSTANCE;
    }

    private static PyObject sslRead(PyObject[] args) {
        Arguments.exactly("_SSLSocket.read", args, null, 2);
        Ssl ssl = object(args[0]);
        int length = Numbers.asIndex(args[1]).intValue();
        if (length <= 0) {
            return PyBytes.of(new byte[0]);
        }
        byte[] buffer = new byte[length];
        int count = read(ssl, buffer, length);
        if (count <= 0) {
            return PyBytes.of(new byte[0]);
        }
        byte[] out = new byte[count];
        System.arraycopy(buffer, 0, out, 0, count);
        return PyBytes.of(out);
    }

    private static PyObject sslReadInto(PyObject[] args) {
        Arguments.exactly("_SSLSocket.read", args, null, 3);
        Ssl ssl = object(args[0]);
        PyMemoryView view = PyMemoryView.of(args[1]);
        int length = Numbers.asIndex(args[2]).intValue();
        int room = view.byteLength();
        if (length > room) {
            length = room;
        }
        if (length <= 0) {
            return PyInt.of(0);
        }
        byte[] buffer = new byte[length];
        int count = read(ssl, buffer, length);
        if (count > 0) {
            view.writeWindow(0, buffer, 0, count);
            return PyInt.of(count);
        }
        return PyInt.of(0);
    }

    private static int read(Ssl ssl, byte[] buffer, int length) {
        try {
            return blocking(ssl, () -> {
                ssl.socket().setSoTimeout(ssl.timeoutMillis);
                return Gil.blockingIO(() -> {
                    InputStream in = ssl.socket().getInputStream();
                    return in.read(buffer, 0, length);
                });
            });
        } catch (RuntimeException failure) {
            throw translate(failure, ssl.timeoutMillis, ssl.nonBlocking, true, false);
        }
    }

    private static PyObject sslWrite(PyObject[] args) {
        Arguments.exactly("_SSLSocket.write", args, null, 2);
        Ssl ssl = object(args[0]);
        byte[] data = PyMemoryView.bufferOctets(args[1]);
        try {
            blocking(ssl, () -> {
                ssl.socket().setSoTimeout(ssl.timeoutMillis);
                Gil.blockingIO(() -> {
                    OutputStream out = ssl.socket().getOutputStream();
                    out.write(data);
                    out.flush();
                    return null;
                });
                return null;
            });
            return PyInt.of(data.length);
        } catch (RuntimeException failure) {
            throw translate(failure, ssl.timeoutMillis, ssl.nonBlocking, false, true);
        }
    }

    private static PyObject sslGetPeerCert(PyObject[] args) {
        Arguments.exactly("_SSLSocket.getpeercert", args, null, 2);
        Ssl ssl = object(args[0]);
        boolean binary = Slots.truth(args[1]);
        Ctx ctx = CONTEXTS.get(ssl.ctxId);
        X509Certificate leaf = peerLeaf(ssl);
        if (leaf == null) {
            return PyNone.INSTANCE;
        }
        try {
            if (binary) {
                return PyBytes.of(leaf.getEncoded());
            }
            if (ctx != null && ctx.verifyMode == 0) {
                // The reference returns an empty dict when the certificate was not validated.
                return PyDict.empty();
            }
            return certDict(leaf, true);
        } catch (Exception failure) {
            throw raiseNamed("SSLError", PyInt.of(1), PyStr.of(failure.getMessage()));
        }
    }

    private static PyObject sslGetChain(PyObject[] args) {
        Arguments.exactly("_SSLSocket.get_verified_chain", args, null, 2);
        Ssl ssl = object(args[0]);
        Slots.truth(args[1]);
        PyList out = PyList.of();
        for (java.security.cert.Certificate cert : peerChain(ssl)) {
            try {
                out.add(PyBytes.of(cert.getEncoded()));
            } catch (Exception failure) {
                throw raiseNamed("SSLError", PyInt.of(1), PyStr.of(failure.getMessage()));
            }
        }
        return out;
    }

    private static PyObject sslAlpn(PyObject[] args) {
        Arguments.exactlyOne("_SSLSocket.selected_alpn_protocol", args, null);
        Ssl ssl = object(args[0]);
        String protocol = ssl.socket().getApplicationProtocol();
        if (protocol == null || protocol.isEmpty()) {
            return PyNone.INSTANCE;
        }
        return PyStr.of(protocol);
    }

    private static PyObject sslCipher(PyObject[] args) {
        Arguments.exactlyOne("_SSLSocket.cipher", args, null);
        Ssl ssl = object(args[0]);
        SSLSession session = ssl.socket().getSession();
        String suite = session.getCipherSuite();
        if (suite == null) {
            return PyNone.INSTANCE;
        }
        return PyTuple.of(
                PyStr.of(opensslCipherName(suite)),
                PyStr.of(session.getProtocol()),
                PyInt.of(cipherBits(suite)));
    }

    private static PyObject sslSharedCiphers(PyObject[] args) {
        Arguments.exactlyOne("_SSLSocket.shared_ciphers", args, null);
        object(args[0]);
        // JSSE does not expose the peer's offered suite list, so there is nothing true to
        // report. The reference itself returns None on the client side.
        return PyNone.INSTANCE;
    }

    private static PyObject sslPending(PyObject[] args) {
        Arguments.exactlyOne("_SSLSocket.pending", args, null);
        Ssl ssl = object(args[0]);
        try {
            return PyInt.of(ssl.socket().getInputStream().available());
        } catch (IOException failure) {
            return PyInt.of(0);
        }
    }

    private static PyObject sslShutdown(PyObject[] args) {
        Arguments.exactlyOne("_SSLSocket.shutdown", args, null);
        Ssl ssl = object(args[0]);
        if (ssl.closed) {
            return PyNone.INSTANCE;
        }
        try {
            // Closing the TLS stream sends close_notify. JSSE does not support unwrapping all
            // the way back to a usable plaintext socket, so this is the honest end of the TLS
            // layer and `unwrap()` returns a socket that is already finished.
            ssl.socket().getOutputStream().close();
        } catch (Exception ignored) {
            // Shutting down a connection whose peer has gone is not an error here.
        }
        ssl.closed = true;
        return PyNone.INSTANCE;
    }

    private static PyObject sslVersion(PyObject[] args) {
        Arguments.exactlyOne("_SSLSocket.version", args, null);
        Ssl ssl = object(args[0]);
        return PyStr.of(ssl.socket().getSession().getProtocol());
    }

    private static PyObject sslChannelBinding(PyObject[] args) {
        Arguments.exactly("_SSLSocket.get_channel_binding", args, null, 2);
        Ssl ssl = object(args[0]);
        String type = args[1] instanceof PyNone ? "tls-unique" : text(args[1]);
        if (type.equals("tls-unique")) {
            throw Slots.valueError("invalid channel binding type");
        }
        if (!type.equals("tls-server-end-point")) {
            throw Slots.valueError("invalid channel binding type");
        }
        X509Certificate leaf = peerLeaf(ssl);
        if (leaf == null) {
            return PyNone.INSTANCE;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(leaf.getEncoded());
            return PyBytes.of(digest);
        } catch (Exception failure) {
            throw raiseNamed("SSLError", PyInt.of(1), PyStr.of(failure.getMessage()));
        }
    }

    private static PyObject sslSession(PyObject[] args) {
        Arguments.exactlyOne("_SSLSocket.session", args, null);
        Ssl ssl = object(args[0]);
        SSLSession session = ssl.socket().getSession();
        if (session == null) {
            return PyNone.INSTANCE;
        }
        byte[] id;
        try {
            id = session.getId();
        } catch (RuntimeException failure) {
            id = new byte[0];
        }
        long seconds = session.getCreationTime() / 1000L;
        return PyTuple.of(
                PyBytes.of(id),
                PyInt.of(seconds),
                PyInt.of(0),
                PyInt.of(0),
                PyBool.FALSE);
    }

    private static PyObject sslSetSession(PyObject[] args) {
        Arguments.exactly("_SSLSocket.session", args, null, 2);
        object(args[0]);
        // JSSE cannot resume from a Python-side session object; it keeps its own session cache.
        return PyNone.INSTANCE;
    }

    private static PyObject sslSessionReused(PyObject[] args) {
        Arguments.exactlyOne("_SSLSocket.session_reused", args, null);
        object(args[0]);
        // JSSE does not report session reuse; claiming either answer would be a guess.
        return PyBool.FALSE;
    }

    private static PyObject sslSetContext(PyObject[] args) {
        Arguments.exactly("_SSLSocket.context", args, null, 2);
        Ssl ssl = object(args[0]);
        ssl.ctxId = Numbers.asIndex(args[1]).intValue();
        return PyNone.INSTANCE;
    }

    // --- module helpers ----------------------------------------------------------------------

    private static PyObject randBytes(PyObject[] args) {
        Arguments.exactlyOne("RAND_bytes", args, null);
        int count = Numbers.asIndex(args[0]).intValue();
        if (count < 0) {
            throw Slots.valueError("RAND_bytes: number of bytes must be non-negative");
        }
        byte[] out = new byte[count];
        new SecureRandom().nextBytes(out);
        return PyBytes.of(out);
    }

    private static PyObject defaultVerifyPaths(PyObject[] args) {
        Arguments.none("get_default_verify_paths", args, null);
        String cacerts = System.getProperty("java.home") + "/lib/security/cacerts";
        String dir = System.getProperty("java.home") + "/lib/security";
        return PyTuple.of(
                PyStr.of("SSL_CERT_FILE"),
                PyStr.of(cacerts),
                PyStr.of("SSL_CERT_DIR"),
                PyStr.of(dir));
    }

    private static PyObject octets(PyObject[] args) {
        Arguments.exactlyOne("memoryview", args, null);
        // The reference asks for a C-contiguous buffer, and a strided view is refused, not copied.
        if (args[0] instanceof PyMemoryView view && !view.cContiguous()) {
            throw Slots.bufferError("memoryview: underlying buffer is not C-contiguous");
        }
        return PyBytes.of(PyMemoryView.bufferOctets(args[0]));
    }

    private static PyObject pemEncode(PyObject[] args) {
        Arguments.exactlyOne("PEM", args, null);
        byte[] der = PyMemoryView.bufferOctets(args[0]);
        StringBuilder out = new StringBuilder();
        out.append("-----BEGIN CERTIFICATE-----\n");
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        out.append(body).append('\n');
        out.append("-----END CERTIFICATE-----\n");
        return PyStr.of(out.toString());
    }

    private static PyObject certInfo(PyObject[] args) {
        Arguments.exactlyOne("certificate", args, null);
        byte[] der = PyMemoryView.bufferOctets(args[0]);
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(der));
            return certDict(cert, true);
        } catch (Exception failure) {
            throw raiseNamed("SSLError", PyInt.of(1), PyStr.of(failure.getMessage()));
        }
    }

    private static PyObject decodeCert(PyObject[] args) {
        Arguments.exactlyOne("_test_decode_cert", args, null);
        String path = text(args[0]);
        List<X509Certificate> certs = parseCertificates(readFile(path), path);
        if (certs.isEmpty()) {
            throw raiseNamed("SSLError", PyInt.of(1), PyStr.of("no certificate found in " + path));
        }
        return certDict(certs.get(0), true);
    }

    // --- certificate parsing -----------------------------------------------------------------

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----\\s*([\\s\\S]*?)-----END \\1-----");

    private static List<X509Certificate> parseCertificates(byte[] data, String where) {
        List<X509Certificate> out = new ArrayList<>();
        CertificateFactory factory;
        try {
            factory = CertificateFactory.getInstance("X.509");
        } catch (Exception missing) {
            return out;
        }
        // The JDK's PEM reader stops at the first block it does not understand, and the reference's
        // test data keeps the private key above the certificate. Scan the blocks here instead, so a
        // key block is skipped rather than ending the file.
        String text = new String(data, StandardCharsets.US_ASCII);
        Matcher matcher = PEM_BLOCK.matcher(text);
        boolean sawPem = false;
        while (matcher.find()) {
            sawPem = true;
            String type = matcher.group(1);
            try {
                byte[] der = Base64.getMimeDecoder().decode(matcher.group(2));
                if (type.equals("CERTIFICATE") || type.equals("X509 CERTIFICATE")) {
                    out.add((X509Certificate) factory.generateCertificate(
                            new ByteArrayInputStream(der)));
                } else if (type.equals("PKCS7") || type.equals("CMS")) {
                    for (java.security.cert.Certificate cert
                            : factory.generateCertificates(new ByteArrayInputStream(der))) {
                        if (cert instanceof X509Certificate x509) {
                            out.add(x509);
                        }
                    }
                }
            } catch (Exception ignored) {
                // A malformed block does not stop the ones after it.
            }
        }
        if (!sawPem) {
            try {
                Collection<? extends java.security.cert.Certificate> certs =
                        factory.generateCertificates(new ByteArrayInputStream(data));
                for (java.security.cert.Certificate cert : certs) {
                    if (cert instanceof X509Certificate x509) {
                        out.add(x509);
                    }
                }
            } catch (Exception ignored) {
                // Not DER certificates; an empty list is reported by the caller.
            }
        }
        if (out.isEmpty()) {
            // A JKS/PKCS12 trust store, such as the JDK's cacerts, is also accepted.
            try {
                KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
                store.load(new ByteArrayInputStream(data), KEY_PASSWORD);
                for (String alias : java.util.Collections.list(store.aliases())) {
                    java.security.cert.Certificate cert = store.getCertificate(alias);
                    if (cert instanceof X509Certificate x509) {
                        out.add(x509);
                    }
                }
            } catch (Exception ignored) {
                // Reported as "no certificate found".
            }
        }
        return out;
    }

    private static PrivateKey parsePrivateKey(byte[] pem, PyObject passwordArg) throws Exception {
        String text = new String(pem, StandardCharsets.US_ASCII);
        Matcher matcher = PEM_BLOCK.matcher(text);
        while (matcher.find()) {
            String type = matcher.group(1);
            byte[] der = Base64.getMimeDecoder().decode(matcher.group(2));
            switch (type) {
                case "PRIVATE KEY":
                    return generateKey(der);
                case "RSA PRIVATE KEY":
                    return generateKey(wrapPkcs8(der, RSA_ALGORITHM));
                case "EC PRIVATE KEY":
                    throw new UnsupportedOperationException(
                            "SEC1 EC private keys are not supported; convert the key to "
                                    + "PKCS#8 (openssl pkcs8 -topk8)");
                case "ENCRYPTED PRIVATE KEY":
                    // The password is only resolved here, so a callback is not called for a key
                    // that does not need one.
                    return decryptKey(der, passwordChars(passwordArg));
                default:
                    break;
            }
        }
        throw new IllegalArgumentException("no private key found");
    }

    /** OpenSSL's {@code PEM_BUFSIZE}: a password longer than this is refused up front. */
    private static final int PEM_BUFSIZE = 1024;

    /**
     * The password a {@code load_cert_chain} call supplied, in the reference's three shapes.
     *
     * <p>A callable is called with no arguments and may return a string, bytes or a bytearray;
     * anything else is a type error. Bytes are read as one character per octet, which is how
     * PKCS#5 treats a password that is not text.
     */
    private static char[] passwordChars(PyObject arg) {
        boolean callback = Slots.isCallable(arg);
        PyObject value = callback ? Slots.call(arg, new PyObject[0], null) : arg;
        String text;
        if (value instanceof PyStr str) {
            text = str.getValue();
        } else if (value instanceof PyBytes || value instanceof PyByteArray) {
            text = new String(PyBytes.octets(value), StandardCharsets.ISO_8859_1);
        } else if (callback) {
            throw Slots.typeError("password callback must return a string");
        } else {
            throw Slots.typeError("password should be a string or bytes");
        }
        if (text.length() > PEM_BUFSIZE) {
            throw Slots.valueError("password cannot be longer than %d bytes", PEM_BUFSIZE);
        }
        return text.toCharArray();
    }

    /**
     * Decrypts a PKCS#8 {@code EncryptedPrivateKeyInfo} with PBES2 through the JCE.
     *
     * <p>This covers the PBES2 schemes OpenSSL writes by default; a legacy
     * {@code Proc-Type: 4,ENCRYPTED} PEM block is refused rather than guessed at.
     */
    private static PrivateKey decryptKey(byte[] der, char[] password) throws Exception {
        EncryptedPrivateKeyInfo info = new EncryptedPrivateKeyInfo(der);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(info.getAlgName());
        PBEKeySpec spec = new PBEKeySpec(password);
        try {
            SecretKey secret = factory.generateSecret(spec);
            return generateKey(info.getKeySpec(secret).getEncoded());
        } finally {
            spec.clearPassword();
        }
    }

    private static final byte[] RSA_ALGORITHM = {
            0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
    };

    private static byte[] wrapPkcs8(byte[] pkcs1, byte[] algorithm) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] octets = der(0x04, pkcs1);
        byte[] body = concat(version, algorithm, octets);
        return der(0x30, body);
    }

    private static PrivateKey generateKey(byte[] pkcs8) throws Exception {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8);
        for (String algorithm : List.of("RSA", "EC", "DSA", "Ed25519", "Ed448")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (Exception ignored) {
                // Try the next algorithm.
            }
        }
        throw new IllegalArgumentException("unrecognized private key encoding");
    }

    private static byte[] der(int tag, byte[] body) {
        byte[] length = derLength(body.length);
        byte[] out = new byte[1 + length.length + body.length];
        out[0] = (byte) tag;
        System.arraycopy(length, 0, out, 1, length.length);
        System.arraycopy(body, 0, out, 1 + length.length, body.length);
        return out;
    }

    private static byte[] derLength(int length) {
        if (length < 0x80) {
            return new byte[] {(byte) length};
        }
        int bytes = 0;
        int copy = length;
        while (copy > 0) {
            bytes++;
            copy >>>= 8;
        }
        byte[] out = new byte[1 + bytes];
        out[0] = (byte) (0x80 | bytes);
        for (int i = bytes; i > 0; i--) {
            out[i] = (byte) (length & 0xff);
            length >>>= 8;
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    // --- certificate dictionaries ------------------------------------------------------------

    private static PyDict certDict(X509Certificate cert, boolean includeAlt) {
        PyDict out = PyDict.empty();
        out.put(PyStr.interned("subject"), distinguishedName(cert.getSubjectX500Principal()
                .getName(javax.security.auth.x500.X500Principal.RFC1779)));
        out.put(PyStr.interned("issuer"), distinguishedName(cert.getIssuerX500Principal()
                .getName(javax.security.auth.x500.X500Principal.RFC1779)));
        out.put(PyStr.interned("version"), PyInt.of(cert.getVersion()));
        out.put(PyStr.interned("serialNumber"),
                PyStr.of(cert.getSerialNumber().toString(16).toUpperCase(Locale.ROOT)));
        out.put(PyStr.interned("notBefore"), PyStr.of(certTime(cert.getNotBefore())));
        out.put(PyStr.interned("notAfter"), PyStr.of(certTime(cert.getNotAfter())));
        if (includeAlt) {
            try {
                Collection<List<?>> names = cert.getSubjectAlternativeNames();
                if (names != null && !names.isEmpty()) {
                    List<PyObject> alt = new ArrayList<>();
                    for (List<?> entry : names) {
                        int type = ((Number) entry.get(0)).intValue();
                        Object value = entry.get(1);
                        alt.add(altEntry(type, value));
                    }
                    out.put(PyStr.interned("subjectAltName"),
                            PyTuple.of(alt.toArray(new PyObject[0])));
                }
            } catch (Exception ignored) {
                // A certificate without a parsable SAN simply has no such entry.
            }
        }
        return out;
    }

    private static PyObject altEntry(int type, Object value) {
        if (type == 0) {
            // The reference prints every otherName as a placeholder, because OpenSSL decodes
            // its contents and it does not.
            return PyTuple.of(PyStr.of("othername"), PyStr.of("<unsupported>"));
        }
        if (type == 4) {
            return PyTuple.of(PyStr.of("DirName"),
                    distinguishedName(String.valueOf(value)));
        }
        String name = altName(type);
        if (value instanceof byte[] bytes) {
            return PyTuple.of(PyStr.of(name), PyStr.of(ipText(bytes)));
        }
        return PyTuple.of(PyStr.of(name), PyStr.of(String.valueOf(value)));
    }

    private static String certTime(Date date) {
        // The reference prints the day with a leading space rather than a zero: `Oct  8 ...`.
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"), Locale.US);
        calendar.setTime(date);
        return String.format(Locale.US, "%s %2d %02d:%02d:%02d %d GMT",
                MONTHS[calendar.get(Calendar.MONTH)],
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                calendar.get(Calendar.SECOND),
                calendar.get(Calendar.YEAR));
    }

    private static final String[] MONTHS = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    private static String altName(int type) {
        switch (type) {
            case 1:
                return "email";
            case 2:
                return "DNS";
            case 4:
                return "DirName";
            case 6:
                return "URI";
            case 7:
                return "IP Address";
            case 8:
                return "Registered ID";
            default:
                return String.valueOf(type);
        }
    }

    /** An IP address from its SAN encoding: four bytes dotted, sixteen as IPv6 groups. */
    private static String ipText(byte[] bytes) {
        if (bytes.length == 4) {
            return String.format(Locale.US, "%d.%d.%d.%d",
                    bytes[0] & 0xff, bytes[1] & 0xff, bytes[2] & 0xff, bytes[3] & 0xff);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < bytes.length; i += 2) {
            if (i > 0) {
                out.append(':');
            }
            out.append(Integer.toHexString(((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff)));
        }
        return out.toString();
    }

    /**
     * Split an RFC 1779 name into the reference's tuple-of-RDN-tuples shape. A multi-valued RDN
     * (joined with {@code +}) is kept together; a comma starts the next RDN.
     */
    private static PyTuple distinguishedName(String name) {
        List<PyTuple> rdns = new ArrayList<>();
        for (String part : splitUnescaped(name, ',')) {
            List<PyObject> attributes = new ArrayList<>();
            for (String attribute : splitUnescaped(part, '+')) {
                int equals = indexOfUnescaped(attribute, '=');
                if (equals < 0) {
                    continue;
                }
                String key = unescape(attribute.substring(0, equals).trim());
                String value = unescape(attribute.substring(equals + 1).trim());
                attributes.add(PyTuple.of(PyStr.of(shortName(key)), PyStr.of(value)));
            }
            if (!attributes.isEmpty()) {
                rdns.add(PyTuple.of(attributes.toArray(new PyObject[0])));
            }
        }
        // RFC 1779 prints a name from the leaf back to the root; the reference keeps the
        // certificate's own order, which is the reverse.
        Collections.reverse(rdns);
        return PyTuple.of(rdns.toArray(new PyObject[0]));
    }

    private static List<String> splitUnescaped(String text, char separator) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                current.append('\\').append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == separator) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out;
    }

    private static int indexOfUnescaped(String text, char wanted) {
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == wanted) {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                out.append(text.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String shortName(String key) {
        // Java writes an attribute it cannot shorten as "OID.1.2.3.4"; the OID itself is what
        // the reference's table is keyed by.
        String oid = key.regionMatches(true, 0, "OID.", 0, 4) ? key.substring(4) : key;
        switch (oid.toUpperCase(Locale.ROOT)) {
            case "CN":
                return "commonName";
            case "C":
                return "countryName";
            case "L":
                return "localityName";
            case "ST":
                return "stateOrProvinceName";
            case "O":
                return "organizationName";
            case "OU":
                return "organizationalUnitName";
            case "SN":
                return "surname";
            case "GN":
                return "givenName";
            case "DC":
                return "domainComponent";
            case "UID":
                return "userId";
            case "1.2.840.113549.1.9.1":
                return "emailAddress";
            case "COMMONNAME":
                return "commonName";
            case "COUNTRYNAME":
                return "countryName";
            case "LOCALITYNAME":
                return "localityName";
            case "STATEORPROVINCENAME":
                return "stateOrProvinceName";
            case "ORGANIZATIONNAME":
                return "organizationName";
            case "ORGANIZATIONALUNITNAME":
                return "organizationalUnitName";
            case "SURNAME":
                return "surname";
            case "GIVENNAME":
                return "givenName";
            case "SERIALNUMBER":
                return "serialNumber";
            case "EMAILADDRESS":
                return "emailAddress";
            case "DOMAINCOMPONENT":
                return "domainComponent";
            case "USERID":
                return "userId";
            default:
                // An OID this table does not know keeps the reference's "OID.x" spelling.
                return key;
        }
    }

    private static X509Certificate peerLeaf(Ssl ssl) {
        List<java.security.cert.Certificate> chain = peerChain(ssl);
        if (chain.isEmpty()) {
            return null;
        }
        return (X509Certificate) chain.get(0);
    }

    private static List<java.security.cert.Certificate> peerChain(Ssl ssl) {
        try {
            java.security.cert.Certificate[] peer = ssl.socket().getSession().getPeerCertificates();
            return peer == null ? List.of() : List.of(peer);
        } catch (SSLPeerUnverifiedException unverified) {
            return List.of();
        } catch (RuntimeException failure) {
            return List.of();
        }
    }

    // --- cipher names ------------------------------------------------------------------------

    private static List<String> supportedSuites() {
        try {
            // `getDefault()` is initialised; a fresh `getInstance("TLS")` is not, and its
            // `getSupportedSSLParameters()` refuses to answer until it is.
            return List.of(SSLContext.getDefault()
                    .getSupportedSSLParameters().getCipherSuites());
        } catch (Exception failure) {
            return List.of();
        }
    }

    /** JSSE's {@code TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384} to OpenSSL's spelling. */
    private static String opensslCipherName(String suite) {
        int with = suite.indexOf("_WITH_");
        if (with < 0) {
            return suite;
        }
        String keyExchange = suite.substring("TLS_".length(), with);
        String rest = suite.substring(with + "_WITH_".length()).replace('_', '-');
        // OpenSSL writes the key size against the algorithm: AES-256 becomes AES256.
        rest = rest.replaceAll("^(AES|CAMELLIA|ARIA)-(128|192|256)", "$1$2");
        return keyExchange.replace('_', '-') + "-" + rest;
    }

    private static int cipherBits(String suite) {
        String name = suite.toUpperCase(Locale.ROOT);
        if (name.contains("CHACHA20")) {
            return 256;
        }
        if (name.contains("AES_256") || name.contains("AES256")) {
            return 256;
        }
        if (name.contains("AES_128") || name.contains("AES128")) {
            return 128;
        }
        if (name.contains("3DES")) {
            return 112;
        }
        return 0;
    }

    private static String cipherProtocol(String suite) {
        if (suite.startsWith("TLS_AES_") || suite.startsWith("TLS_CHACHA20_")) {
            return "TLSv1.3";
        }
        return "TLSv1.2";
    }

    private static volatile List<String> enabledSuites;

    /** The suites the platform enables by default: the only set a cipher string may narrow. */
    private static List<String> jvmEnabledSuites() {
        List<String> cached = enabledSuites;
        if (cached == null) {
            try {
                cached = List.of(SSLContext.getDefault()
                        .getDefaultSSLParameters().getCipherSuites());
            } catch (Exception failure) {
                cached = List.of();
            }
            enabledSuites = cached;
        }
        return cached;
    }

    /**
     * An OpenSSL cipher string, read as far as the platform can honour it.
     *
     * <p>JSSE names suites; it has no OpenSSL cipher grammar. What is accepted here is the part of
     * that grammar that maps onto suite selection: an exact name from {@link #CIPHER_NAMES} or a
     * JSSE suite name, the four bundle words, and a keyword made of the pieces suite names are
     * built from ({@code AESGCM}, {@code ECDHE+AESGCM}, ...). Exclusions ({@code !}, {@code -})
     * remove what they match. The universe is the platform's default-enabled suite list, so a
     * string can narrow selection but cannot switch on a suite the platform turned off.
     */
    private static List<String> mapCipherSpec(String spec) {
        List<String> available = jvmEnabledSuites();
        List<String> selected = null;
        for (String token : spec.split("[: ,]+")) {
            if (token.isEmpty()) {
                continue;
            }
            boolean exclude = token.charAt(0) == '!';
            boolean append = token.charAt(0) == '+';
            String cleaned = token;
            while (!cleaned.isEmpty() && "!+@-".indexOf(cleaned.charAt(0)) >= 0) {
                cleaned = cleaned.substring(1);
            }
            if (cleaned.isEmpty()) {
                continue;
            }
            String upper = cleaned.toUpperCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            if (upper.equals("DEFAULT") || upper.equals("ALL") || upper.equals("HIGH")
                    || upper.equals("COMPLEMENTOFDEFAULT")) {
                matches.addAll(available);
            } else {
                String exact = cipherSuite(upper);
                if (exact != null && available.contains(exact)) {
                    matches.add(exact);
                } else {
                    for (String suite : available) {
                        if (matchesCipherKeyword(opensslCipherName(suite), upper)) {
                            matches.add(suite);
                        }
                    }
                    if (matches.isEmpty() && exact != null) {
                        matches.add(exact);
                    }
                }
            }
            if (matches.isEmpty() && !exclude) {
                return null;
            }
            if (exclude) {
                if (selected == null) {
                    selected = new ArrayList<>(available);
                }
                selected.removeAll(matches);
            } else if (append && selected != null) {
                for (String suite : matches) {
                    if (!selected.contains(suite)) {
                        selected.add(suite);
                    }
                }
            } else {
                selected = new ArrayList<>(matches);
            }
        }
        return selected == null ? new ArrayList<>(available) : selected;
    }

    /**
     * The pieces suite names are built from, longest first so {@code CHACHA20} is not read as
     * {@code C} + {@code HACHA20}. A keyword like {@code AESGCM} is not one word: it is {@code AES}
     * followed by {@code GCM}, and both have to appear in the suite's name.
     */
    private static final String[] CIPHER_PRIMITIVES = {
            "CHACHA20", "POLY1305", "CAMELLIA", "SHA512", "SHA384", "SHA256",
            "ECDHE", "ECDSA", "SHA1", "3DES", "ARIA", "EXPORT", "ANON", "NULL", "AES", "CCM",
            "GCM", "CBC", "RSA", "DHE", "DSS", "DES", "RC4", "MD5", "PSK", "SRP", "SM4", "SEED",
            "IDEA", "SHA", "DH", "128", "192", "256", "384", "512"
    };

    /** Whether every word of {@code keyword} appears in the suite's OpenSSL name. */
    private static boolean matchesCipherKeyword(String name, String keyword) {
        String haystack = name.toUpperCase(Locale.ROOT).replace("_", "").replace("-", "");
        boolean any = false;
        for (String part : keyword.split("\\+")) {
            if (part.isEmpty()) {
                continue;
            }
            int at = 0;
            while (at < part.length()) {
                String piece = null;
                for (String primitive : CIPHER_PRIMITIVES) {
                    if (part.startsWith(primitive, at)) {
                        piece = primitive;
                        break;
                    }
                }
                if (piece == null) {
                    // An unfamiliar word: ask for its letters rather than refusing the string.
                    piece = String.valueOf(part.charAt(at));
                }
                if (!haystack.contains(piece)) {
                    return false;
                }
                at += piece.length();
            }
            any = true;
        }
        return any;
    }

    private static final Map<String, String> CIPHER_NAMES = new HashMap<>();

    static {
        CIPHER_NAMES.put("TLS_AES_256_GCM_SHA384", "TLS_AES_256_GCM_SHA384");
        CIPHER_NAMES.put("TLS_CHACHA20_POLY1305_SHA256", "TLS_CHACHA20_POLY1305_SHA256");
        CIPHER_NAMES.put("TLS_AES_128_GCM_SHA256", "TLS_AES_128_GCM_SHA256");
        CIPHER_NAMES.put("ECDHE-RSA-AES256-GCM-SHA384",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");
        CIPHER_NAMES.put("ECDHE-RSA-AES128-GCM-SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        CIPHER_NAMES.put("ECDHE-ECDSA-AES256-GCM-SHA384",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384");
        CIPHER_NAMES.put("ECDHE-ECDSA-AES128-GCM-SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256");
        CIPHER_NAMES.put("ECDHE-RSA-CHACHA20-POLY1305",
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256");
        CIPHER_NAMES.put("ECDHE-ECDSA-CHACHA20-POLY1305",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256");
        CIPHER_NAMES.put("AES256-GCM-SHA384", "TLS_RSA_WITH_AES_256_GCM_SHA384");
        CIPHER_NAMES.put("AES128-GCM-SHA256", "TLS_RSA_WITH_AES_128_GCM_SHA256");
        CIPHER_NAMES.put("ECDHE-RSA-AES256-SHA384", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384");
        CIPHER_NAMES.put("ECDHE-RSA-AES128-SHA256", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256");
    }

    private static String cipherSuite(String token) {
        String mapped = CIPHER_NAMES.get(token);
        if (mapped != null) {
            return mapped;
        }
        if (token.startsWith("TLS_") || token.startsWith("SSL_")) {
            return token;
        }
        return null;
    }

    private static String curveGroup(String name) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "prime256v1":
            case "secp256r1":
            case "p-256":
                return "secp256r1";
            case "secp384r1":
            case "p-384":
                return "secp384r1";
            case "secp521r1":
            case "p-521":
                return "secp521r1";
            case "x25519":
                return "x25519";
            case "x448":
                return "x448";
            default:
                return null;
        }
    }

    // --- errors ---------------------------------------------------------------

    private static RuntimeException translate(Throwable failure, int timeoutMillis,
            boolean nonBlocking, boolean reading, boolean writing) {
        if (failure instanceof PyRaise raise) {
            return raise;
        }
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                if (nonBlocking) {
                    return raiseNamed(reading ? "SSLWantReadError" : "SSLWantWriteError",
                            PyInt.of(reading ? 2 : 3),
                            PyStr.of("The operation did not complete ("
                                    + (reading ? "read" : "write") + ")"));
                }
                return PyRaise.raise(ExceptionTypes.TIMEOUT_ERROR,
                        new PyObject[] {PyStr.of("timed out")});
            }
            if (cause instanceof java.security.cert.CertPathValidatorException
                    || cause instanceof java.security.cert.CertPathBuilderException
                    || cause instanceof java.security.cert.CertificateException) {
                String message = cause.getMessage() == null ? "" : cause.getMessage();
                return certVerificationError(message);
            }
            if (cause instanceof SSLException) {
                String message = cause.getMessage() == null ? "" : cause.getMessage();
                if (message.contains("PKIX path building failed")
                        || message.contains("unable to find valid certification path")) {
                    return certVerificationError(message);
                }
                if (message.contains("close_notify")) {
                    return raiseNamed("SSLEOFError", "SSL", "UNEXPECTED_EOF_WHILE_READING",
                            PyInt.of(8), PyStr.of(message));
                }
                return raiseNamed("SSLError", "SSL", null,
                        PyInt.of(1), PyStr.of("[SSL] " + message));
            }
            cause = cause.getCause();
        }
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        if (failure instanceof IOException) {
            return raiseNamed("SSLError", PyInt.of(1), PyStr.of("[SSL] " + message));
        }
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        return raiseNamed("SSLError", PyInt.of(1), PyStr.of("[SSL] " + message));
    }

    /**
     * A raise of one of the module's exception classes, built the way CPython builds one.
     *
     * <p>{@code PyErr_SetString} does not hand back a bare value: it calls the class, so a
     * {@code SSLError} made here runs the {@code __init__} the Python half declares — the one that
     * fills {@code library} and {@code reason} — and every one of these classes derives from
     * {@code OSError}, which is a {@link PyOSError} in Java rather than the generic
     * {@link PyBaseException}. Constructing the value directly used to produce the wrong Java class,
     * and reading {@code errno} off it then failed a cast.
     */
    private static PyRaise raiseNamed(String name, PyObject... args) {
        return PyRaise.of(sslException(name, null, null, args));
    }

    /**
     * The same raise, with the {@code library}/{@code reason} pair the reference attaches when it
     * knows where the failure came from. Both names exist on every error the module raises — on a
     * value made by hand they do not — so they are always written here, {@code None} included.
     */
    private static PyRaise raiseNamed(
            String name, String library, String reason, PyObject... args) {
        return PyRaise.of(sslException(name, library, reason, args));
    }

    private static PyBaseException sslException(
            String name, String library, String reason, PyObject... args) {
        PyObject found = module.dictionary().get(PyStr.interned(name));
        if (!(found instanceof PyType type)) {
            throw new IllegalStateException("_ssl is missing " + name);
        }
        PyBaseException made = (PyBaseException) Classes.typeCall(type, args, null);
        made.setAttr("library", library == null ? PyNone.INSTANCE : PyStr.of(library));
        made.setAttr("reason", reason == null ? PyNone.INSTANCE : PyStr.of(reason));
        return made;
    }

    /** {@code CERTIFICATE_VERIFY_FAILED}, with the code and message the reference fills in. */
    private static PyRaise certVerificationError(String message) {
        PyBaseException made = sslException("SSLCertVerificationError", "SSL",
                "CERTIFICATE_VERIFY_FAILED",
                PyInt.of(1), PyStr.of("[SSL: CERTIFICATE_VERIFY_FAILED] " + message),
                PyStr.of(message));
        made.setAttr("verify_code", PyInt.of(1));
        made.setAttr("verify_message", PyStr.of(message));
        return PyRaise.of(made);
    }

    private static PyRaise fileNotFound(String path) {
        return PyRaise.of(PyOSError.create(
                ExceptionTypes.FILE_NOT_FOUND_ERROR,
                new PyObject[] {PyInt.of(2), PyStr.of("No such file or directory"),
                        PyStr.of(path)}));
    }

    private static byte[] readFile(String path) {
        try {
            return Files.readAllBytes(Path.of(path));
        } catch (NoSuchFileException missing) {
            throw fileNotFound(path);
        } catch (IOException failure) {
            throw raiseNamed("SSLError", PyInt.of(1), PyStr.of(failure.getMessage()));
        }
    }

    private static String text(PyObject value) {
        if (value instanceof PyBytes bytes) {
            return new String(bytes.getValue(), StandardCharsets.UTF_8);
        }
        return ((PyStr) Arguments.text(0, value)).getValue();
    }

    private static String source() {
        try (InputStream stream = SslModule.class.getResourceAsStream(SOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("the runtime is missing " + SOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + SOURCE, e);
        }
    }

    /** Trust everything: what {@code CERT_NONE} means. */
    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType,
                Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType,
                Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType,
                SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType,
                SSLEngine engine) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
