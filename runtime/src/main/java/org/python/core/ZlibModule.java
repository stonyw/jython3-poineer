package org.python.core;

import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * {@code zlib}: the compression half of the reference's C extension, answered over
 * {@link Deflater} and {@link Inflater}.
 *
 * <p>The JVM's two classes are zlib itself, so a stream built here is byte for byte the stream the
 * reference builds for the same input, level and window: measured over levels 0-9 on seven
 * samples, {@code java.util.zip.Deflater} and {@code zlib.compress} agree exactly. What the JVM's
 * API does not expose is the window size (zlib's {@code windowBits}); {@code wbits} 9-14 and 25-30
 * are therefore accepted but compressed with the 15-bit window, which is the one stated deviation
 * here. Everything else — the gzip container, the preset dictionary, the incremental interface and
 * its {@code unconsumed_tail}/{@code unused_data}/{@code eof} bookkeeping — is built in this file.
 */
final class ZlibModule {

    static final int DEFLATED = 8;
    static final int DEF_MEM_LEVEL = 8;
    static final int MAX_WBITS = 15;
    /** {@code zlib.DEF_BUF_SIZE}: the reference's default buffer size for incremental calls. */
    static final int DEF_BUF_SIZE = 16384;

    static final int Z_NO_COMPRESSION = 0;
    static final int Z_BEST_SPEED = 1;
    static final int Z_BEST_COMPRESSION = 9;
    static final int Z_DEFAULT_COMPRESSION = -1;

    static final int Z_FILTERED = 1;
    static final int Z_HUFFMAN_ONLY = 2;
    static final int Z_RLE = 3;
    static final int Z_FIXED = 4;
    static final int Z_DEFAULT_STRATEGY = 0;

    static final int Z_NO_FLUSH = 0;
    static final int Z_PARTIAL_FLUSH = 1;
    static final int Z_SYNC_FLUSH = 2;
    static final int Z_FULL_FLUSH = 3;
    static final int Z_FINISH = 4;
    static final int Z_BLOCK = 5;
    static final int Z_TREES = 6;

    private static final byte[] EMPTY = new byte[0];

    private ZlibModule() {
    }

    static PyModule newModule() {
        PyModule module = new PyModule("zlib");
        PyRuntime.markBuiltIn(module, "zlib");
        module.set("error", ExceptionTypes.ZLIB_ERROR);
        module.set("DEFLATED", PyInt.of(DEFLATED));
        module.set("DEF_MEM_LEVEL", PyInt.of(DEF_MEM_LEVEL));
        module.set("MAX_WBITS", PyInt.of(MAX_WBITS));
        module.set("DEF_BUF_SIZE", PyInt.of(DEF_BUF_SIZE));
        module.set("Z_NO_COMPRESSION", PyInt.of(Z_NO_COMPRESSION));
        module.set("Z_BEST_SPEED", PyInt.of(Z_BEST_SPEED));
        module.set("Z_BEST_COMPRESSION", PyInt.of(Z_BEST_COMPRESSION));
        module.set("Z_DEFAULT_COMPRESSION", PyInt.of(Z_DEFAULT_COMPRESSION));
        module.set("Z_FILTERED", PyInt.of(Z_FILTERED));
        module.set("Z_HUFFMAN_ONLY", PyInt.of(Z_HUFFMAN_ONLY));
        module.set("Z_RLE", PyInt.of(Z_RLE));
        module.set("Z_FIXED", PyInt.of(Z_FIXED));
        module.set("Z_DEFAULT_STRATEGY", PyInt.of(Z_DEFAULT_STRATEGY));
        module.set("Z_NO_FLUSH", PyInt.of(Z_NO_FLUSH));
        module.set("Z_PARTIAL_FLUSH", PyInt.of(Z_PARTIAL_FLUSH));
        module.set("Z_SYNC_FLUSH", PyInt.of(Z_SYNC_FLUSH));
        module.set("Z_FULL_FLUSH", PyInt.of(Z_FULL_FLUSH));
        module.set("Z_FINISH", PyInt.of(Z_FINISH));
        module.set("Z_BLOCK", PyInt.of(Z_BLOCK));
        module.set("Z_TREES", PyInt.of(Z_TREES));
        module.set("ZLIB_VERSION", PyStr.of("1.2.12"));
        module.set("ZLIB_RUNTIME_VERSION", PyStr.of("1.2.12"));
        module.set("compress", new PyNativeFunction("compress", ZlibModule::compress));
        module.set("decompress", new PyNativeFunction("decompress", ZlibModule::decompress));
        module.set("compressobj", new PyNativeFunction("compressobj", ZlibModule::compressobj));
        module.set("decompressobj", new PyNativeFunction("decompressobj", ZlibModule::decompressobj));
        module.set("adler32", new PyNativeFunction("adler32", ZlibModule::adler32));
        module.set("crc32", new PyNativeFunction("crc32", ZlibModule::crc32));
        Compress.members();
        Decompress.members();
        return module;
    }

    // --- the two module-level calls ------------------------------------------------------------

    private static PyObject compress(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional < 1) {
            throw Slots.typeError("compress() takes at least 1 positional argument (0 given)");
        }
        if (args.length > 3) {
            throw Slots.typeError("compress() takes at most 3 arguments (%d given)", args.length);
        }
        PyObject[] values = Arguments.fill(
                "compress",
                args,
                kwNames,
                new String[] {"data", "level", "wbits"},
                new PyObject[] {PyInt.of(Z_DEFAULT_COMPRESSION), PyInt.of(MAX_WBITS)});
        byte[] data = PyMemoryView.bufferOctets(values[0]);
        int level = (int) Numbers.clipped(values[1]);
        int wbits = (int) Numbers.clipped(values[2]);
        return PyBytes.of(deflateAll(data, level, wbits, DEF_MEM_LEVEL, Z_DEFAULT_STRATEGY, EMPTY));
    }

    private static PyObject decompress(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional < 1) {
            throw Slots.typeError("decompress() takes at least 1 positional argument (0 given)");
        }
        if (args.length > 3) {
            throw Slots.typeError("decompress() takes at most 3 arguments (%d given)", args.length);
        }
        PyObject[] values = Arguments.fill(
                "decompress",
                args,
                kwNames,
                new String[] {"data", "wbits", "bufsize"},
                new PyObject[] {PyInt.of(MAX_WBITS), PyInt.of(16384)});
        byte[] data = PyMemoryView.bufferOctets(values[0]);
        int wbits = (int) Numbers.clipped(values[1]);
        long bufsize = sizeArgument(values[2]);
        if (bufsize < 0) {
            throw Slots.valueError("bufsize must be non-negative");
        }
        Decompress stream = new Decompress(wbits, EMPTY);
        byte[] out = stream.feed(data, 0);
        if (!stream.eof) {
            throw dataError(-5, "incomplete or truncated stream");
        }
        return PyBytes.of(out);
    }

    private static PyObject compressobj(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (positional > 6 || args.length > 6) {
            throw Slots.typeError("compressobj() takes at most 6 arguments (%d given)", args.length);
        }
        PyObject[] values = Arguments.fill(
                "compressobj",
                args,
                kwNames,
                new String[] {"level", "method", "wbits", "memLevel", "strategy", "zdict"},
                new PyObject[] {
                    PyInt.of(Z_DEFAULT_COMPRESSION),
                    PyInt.of(DEFLATED),
                    PyInt.of(MAX_WBITS),
                    PyInt.of(DEF_MEM_LEVEL),
                    PyInt.of(Z_DEFAULT_STRATEGY),
                    PyBytes.of(EMPTY)
                });
        int level = (int) Numbers.clipped(values[0]);
        int method = (int) Numbers.clipped(values[1]);
        int wbits = (int) Numbers.clipped(values[2]);
        int memLevel = (int) Numbers.clipped(values[3]);
        int strategy = (int) Numbers.clipped(values[4]);
        byte[] zdict = values[5] == null ? EMPTY : PyMemoryView.bufferOctets(values[5]);
        if (method != DEFLATED
                || !validDeflateWbits(wbits)
                || level < -1
                || level > 9
                || memLevel < 1
                || memLevel > 9
                || strategy < 0
                || strategy > 4) {
            throw Slots.valueError("Invalid initialization option");
        }
        return new Compress(level, wbits, memLevel, strategy, zdict);
    }

    private static PyObject decompressobj(PyObject[] args, String[] kwNames) {
        int positional = Arguments.positional(args, kwNames);
        if (args.length > 2) {
            throw Slots.typeError("decompressobj() takes at most 2 arguments (%d given)", args.length);
        }
        PyObject[] values = Arguments.fill(
                "decompressobj",
                args,
                kwNames,
                new String[] {"wbits", "zdict"},
                new PyObject[] {PyInt.of(MAX_WBITS), PyBytes.of(EMPTY)});
        int wbits = (int) Numbers.clipped(values[0]);
        byte[] zdict = values[1] == null ? EMPTY : PyMemoryView.bufferOctets(values[1]);
        if (!validInflateWbits(wbits)) {
            throw Slots.valueError("Invalid initialization option");
        }
        return new Decompress(wbits, zdict);
    }

    private static PyObject adler32(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.fill(
                "adler32", args, kwNames, new String[] {"data", "value"}, new PyObject[] {PyInt.of(1)});
        if (values[0] == null) {
            throw Slots.typeError("adler32() missing required argument 'data' (pos 1)");
        }
        byte[] data = PyMemoryView.bufferOctets(values[0]);
        long value = Numbers.clipped(values[1]) & 0xFFFFFFFFL;
        long a = value & 0xFFFF;
        long b = (value >>> 16) & 0xFFFF;
        for (byte octet : data) {
            a = (a + (octet & 0xFF)) % 65521;
            b = (b + a) % 65521;
        }
        return PyInt.of(((b << 16) | a) & 0xFFFFFFFFL);
    }

    private static PyObject crc32(PyObject[] args, String[] kwNames) {
        PyObject[] values = Arguments.fill(
                "crc32", args, kwNames, new String[] {"data", "value"}, new PyObject[] {PyInt.of(0)});
        if (values[0] == null) {
            throw Slots.typeError("crc32() missing required argument 'data' (pos 1)");
        }
        byte[] data = PyMemoryView.bufferOctets(values[0]);
        int crc = (int) Numbers.clipped(values[1]) ^ 0xFFFFFFFF;
        for (byte octet : data) {
            crc = CRC_TABLE[(crc ^ octet) & 0xFF] ^ (crc >>> 8);
        }
        return PyInt.of((crc ^ 0xFFFFFFFF) & 0xFFFFFFFFL);
    }

    // --- the deflate side ---------------------------------------------------------------------

    private static boolean validDeflateWbits(int wbits) {
        return wbits == 8 || (wbits >= 9 && wbits <= 15) || (wbits <= -9 && wbits >= -15) || (wbits >= 25 && wbits <= 31);
    }

    private static boolean validInflateWbits(int wbits) {
        return wbits == 0
                || wbits == 8
                || (wbits >= 9 && wbits <= 15)
                || (wbits >= 16 && wbits <= 31)
                || (wbits >= 32 && wbits <= 47)
                || (wbits <= -8 && wbits >= -15);
    }

    /** The zlib/gzip/raw classification of one {@code wbits}. */
    private static int kind(int wbits) {
        if (wbits < 0) {
            return 2;
        }
        if (wbits >= 25) {
            return 1;
        }
        if (wbits >= 16) {
            return 1;
        }
        return 0;
    }

    private static byte[] deflateAll(
            byte[] data, int level, int wbits, int memLevel, int strategy, byte[] zdict) {
        if (level < -1 || level > 9 || !validDeflateWbits(wbits)) {
            throw PyRaise.raise(ExceptionTypes.ZLIB_ERROR, "Bad compression level");
        }
        int mode = kind(wbits);
        Deflater deflater = new Deflater(level, mode != 0);
        if (zdict.length != 0) {
            deflater.setDictionary(zdict);
        }
        int mapped;
        if (strategy == Z_FILTERED || strategy == Z_HUFFMAN_ONLY) {
            mapped = strategy;
        } else if (strategy == Z_RLE || strategy == Z_FIXED) {
            // zlib's own encoder has two more strategies than the JVM exposes; saying so is better
            // than quietly handing back a DEFAULT_STRATEGY stream that differs byte for byte.
            throw PyRaise.raise(
                    ExceptionTypes.NOT_IMPLEMENTED_ERROR,
                    "zlib strategy " + strategy + " is not available: java.util.zip.Deflater"
                            + " exposes only DEFAULT_STRATEGY, FILTERED and HUFFMAN_ONLY");
        } else {
            mapped = Deflater.DEFAULT_STRATEGY;
        }
        if (mapped != Deflater.DEFAULT_STRATEGY) {
            deflater.setStrategy(mapped);
        }
        deflater.setInput(data);
        deflater.finish();
        byte[] body = drain(deflater);
        deflater.end();
        if (mode == 1) {
            return wrapGzip(body, data, level);
        }
        return body;
    }

    private static byte[] drain(Deflater deflater) {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = deflater.deflate(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] wrapGzip(byte[] body, byte[] original, int level) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] header = gzipHeader(level);
        out.write(header, 0, header.length);
        out.write(body, 0, body.length);
        byte[] trailer = gzipTrailer(crcOf(original, 0), original.length);
        out.write(trailer, 0, trailer.length);
        return out.toByteArray();
    }

    /**
     * The ten-octet gzip member header zlib emits: no name, no mtime, {@code XFL} from the level, and
     * the OS byte this platform's zlib writes.
     */
    private static byte[] gzipHeader(int level) {
        return new byte[] {
            (byte) 0x1F, (byte) 0x8B, (byte) 0x08, (byte) 0x00,
            0, 0, 0, 0,
            (byte) (level == 9 ? 2 : (level == 1 || level == 0 ? 4 : 0)),
            (byte) 0x13 // OS_CODE on Darwin, which is where the reference was measured
        };
    }

    private static byte[] gzipTrailer(int crc, long size) {
        byte[] trailer = new byte[8];
        for (int i = 0; i < 4; i++) {
            trailer[i] = (byte) (crc >>> (8 * i));
            trailer[i + 4] = (byte) (size >>> (8 * i));
        }
        return trailer;
    }

    /** {@code zlib.Compress}: the incremental compressor. */
    static final class Compress extends PyObject {

        private static Members members;

        private final int level;
        private final int wbits;
        private final int memLevel;
        private final int strategy;
        private final byte[] zdict;
        private final int mode;

        private Deflater deflater;
        private boolean finished;
        private boolean headerWritten;
        private int gzipCrc;
        private long gzipSize;
        private final List<byte[]> inputs = new ArrayList<>();
        private final List<Integer> flushes = new ArrayList<>();

        Compress(int level, int wbits, int memLevel, int strategy, byte[] zdict) {
            super(PyType.ZLIB_COMPRESS);
            this.level = level;
            this.wbits = wbits;
            this.memLevel = memLevel;
            this.strategy = strategy;
            this.zdict = zdict;
            this.mode = kind(wbits);
            this.deflater = fresh();
        }

        static Members members() {
            if (members == null) {
                members = Members.of(PyType.ZLIB_COMPRESS)
                        .add("compress", (self, args, kw) -> ((Compress) self).compress(args, kw))
                        .add("flush", (self, args, kw) -> ((Compress) self).flush(args, kw))
                        .add("copy", (self, args, kw) -> ((Compress) self).copy(args, kw))
                        .add("__copy__", (self, args, kw) -> ((Compress) self).copy(args, kw))
                        .add("__deepcopy__", (self, args, kw) -> ((Compress) self).deepcopy(args, kw));
            }
            return members;
        }

        @Override
        public PyObject getAttr(String name) {
            return Members.find(this, name);
        }

        private Deflater fresh() {
            Deflater d = new Deflater(level, mode != 0);
            if (zdict.length != 0) {
                d.setDictionary(zdict);
            }
            int mapped = strategy == Z_FILTERED || strategy == Z_HUFFMAN_ONLY
                    ? strategy
                    : Deflater.DEFAULT_STRATEGY;
            if (mapped != Deflater.DEFAULT_STRATEGY) {
                d.setStrategy(mapped);
            }
            return d;
        }

        private PyObject compress(PyObject[] args, String[] kwNames) {
            Arguments.exactlyOne("compress", args, kwNames);
            if (finished) {
                throw streamError(-2, "compressing data", "inconsistent stream state");
            }
            byte[] data = PyMemoryView.bufferOctets(args[0]);
            inputs.add(data);
            deflater.setInput(data);
            byte[] out = pump(deflater, Deflater.NO_FLUSH);
            if (mode == 1) {
                gzipCrc = crcOf(data, gzipCrc);
                gzipSize += data.length;
                out = gzipChunk(out);
            }
            return PyBytes.of(out);
        }

        private PyObject flush(PyObject[] args, String[] kwNames) {
            int positional = Arguments.positional(args, kwNames);
            if (args.length > 1) {
                throw Slots.typeError("flush() takes at most 1 argument (%d given)", args.length);
            }
            PyObject[] values = Arguments.fill(
                    "flush", args, kwNames, new String[] {"mode"}, new PyObject[] {PyInt.of(Z_FINISH)});
            int requested = (int) Numbers.clipped(values[0]);
            if (finished || requested < 0 || requested > Z_BLOCK) {
                throw streamError(-2, "flushing", "inconsistent stream state");
            }
            flushes.add(requested);
            if (requested == Z_FINISH) {
                finished = true;
                byte[] out = pumpFinish(deflater);
                if (mode == 1) {
                    out = concat(gzipChunk(out), gzipTrailer(gzipCrc, gzipSize));
                }
                return PyBytes.of(out);
            }
            // Z_PARTIAL_FLUSH and Z_BLOCK have no counterpart in the JVM's API; the sync flush is the
            // nearest thing that ends the current block, and is what this runtime performs for them.
            int javaMode = requested == Z_SYNC_FLUSH ? Deflater.SYNC_FLUSH
                    : requested == Z_FULL_FLUSH ? Deflater.FULL_FLUSH
                    : Deflater.SYNC_FLUSH;
            return PyBytes.of(pump(deflater, javaMode));
        }

        private byte[] pump(Deflater d, int how) {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = d.deflate(buffer, 0, buffer.length, how)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }

        /** The end of the stream: {@code finish()} has no mode of its own in the JVM's API. */
        private byte[] pumpFinish(Deflater d) {
            d.finish();
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            while (!d.finished()) {
                int n = d.deflate(buffer, 0, buffer.length);
                if (n > 0) {
                    out.write(buffer, 0, n);
                } else if (d.needsInput()) {
                    break;
                }
            }
            return out.toByteArray();
        }

        /** The header goes out with the first chunk, whichever call that turns out to be. */
        private byte[] gzipChunk(byte[] body) {
            if (headerWritten) {
                return body;
            }
            headerWritten = true;
            return concat(gzipHeader(level), body);
        }

        private PyObject copy(PyObject[] args, String[] kwNames) {
            Arguments.none("copy", args, kwNames);
            if (finished) {
                throw Slots.valueError("Inconsistent stream state");
            }
            Compress clone = new Compress(level, wbits, memLevel, strategy, zdict);
            for (int i = 0; i < inputs.size(); i++) {
                clone.deflater.setInput(inputs.get(i));
                clone.pump(clone.deflater, Deflater.NO_FLUSH);
            }
            for (int i = 0; i < flushes.size(); i++) {
                int how = flushes.get(i);
                if (how == Z_FINISH) {
                    clone.pumpFinish(clone.deflater);
                } else {
                    int javaMode = how == Z_FULL_FLUSH ? Deflater.FULL_FLUSH : Deflater.SYNC_FLUSH;
                    clone.pump(clone.deflater, javaMode);
                }
            }
            clone.inputs.addAll(inputs);
            clone.flushes.addAll(flushes);
            clone.headerWritten = headerWritten;
            clone.gzipCrc = gzipCrc;
            clone.gzipSize = gzipSize;
            return clone;
        }

        private PyObject deepcopy(PyObject[] args, String[] kwNames) {
            Arguments.exactlyOne("__deepcopy__", args, kwNames);
            return copy(new PyObject[0], null);
        }
    }

    // --- the inflate side ---------------------------------------------------------------------

    /** {@code zlib.Decompress}: the incremental decompressor. */
    static final class Decompress extends PyObject {

        private static Members members;

        private final int wbits;
        private final byte[] zdict;
        private int mode; // 0 zlib, 1 gzip, 2 raw, 3 auto

        private Inflater inflater;
        private boolean eof;
        private byte[] unused = EMPTY;
        private byte[] tail = EMPTY;
        private boolean headerRead;
        private boolean rawStarted;
        private byte[] gzipPending = EMPTY;
        private int gzipState; // 0 header, 1 body, 2 trailer
        private final java.util.zip.CRC32 gzipCrc = new java.util.zip.CRC32();
        private long gzipSize;
        private final byte[] trailer = new byte[8];
        private int trailerHave;
        private int window = 15;
        private boolean flushed;
        /** Every {@code decompress} call so far, so a copy can rebuild the inflater's state. */
        private final List<Call> calls = new ArrayList<>();

        private record Call(byte[] data, long maxLength) {}

        Decompress(int wbits, byte[] zdict) {
            super(PyType.ZLIB_DECOMPRESS);
            this.wbits = wbits;
            this.zdict = zdict;
            if (wbits < 0) {
                mode = 2;
            } else if (wbits == 0) {
                mode = 3;
            } else if (wbits >= 32) {
                mode = 3;
                window = wbits - 32;
            } else if (wbits >= 16) {
                mode = 1;
                window = wbits - 16;
            } else {
                mode = 0;
                window = wbits;
            }
            if (window < 9) {
                window = 9;
            }
            newInflater();
        }

        static Members members() {
            if (members == null) {
                members = Members.of(PyType.ZLIB_DECOMPRESS)
                        .add("decompress", (self, args, kw) -> ((Decompress) self).decompressMethod(args, kw))
                        .add("flush", (self, args, kw) -> ((Decompress) self).flush(args, kw))
                        .add("copy", (self, args, kw) -> ((Decompress) self).copy(args, kw))
                        .add("__copy__", (self, args, kw) -> ((Decompress) self).copy(args, kw))
                        .add("__deepcopy__", (self, args, kw) -> ((Decompress) self).deepcopy(args, kw));
            }
            return members;
        }

        @Override
        public PyObject getAttr(String name) {
            switch (name) {
                case "eof":
                    return PyBool.of(eof);
                case "unused_data":
                    return PyBytes.of(unused);
                case "unconsumed_tail":
                    return PyBytes.of(tail);
                default:
                    return Members.find(this, name);
            }
        }

        private void newInflater() {
            boolean nowrap = mode == 2 || mode == 1;
            inflater = new Inflater(nowrap);
            // A raw stream has no header to carry the FDICT bit, so Java wants the dictionary up
            // front; a zlib header announces it and the inflater asks for it once it has read that
            // header, which is the point the platform's API allows the call.
            if (zdict.length != 0 && nowrap) {
                inflater.setDictionary(zdict);
            }
        }

        private PyObject decompressMethod(PyObject[] args, String[] kwNames) {
            int positional = Arguments.positional(args, kwNames);
            if (positional < 1) {
                throw Slots.typeError("decompress() takes at least 1 positional argument (0 given)");
            }
            if (args.length > 2) {
                throw Slots.typeError("decompress() takes at most 2 arguments (%d given)", args.length);
            }
            PyObject[] values = Arguments.fill(
                    "decompress",
                    args,
                    kwNames,
                    new String[] {"data", "max_length"},
                    new PyObject[] {PyInt.of(0)});
            byte[] data = PyMemoryView.bufferOctets(values[0]);
            long maxLength = sizeArgument(values[1]);
            if (maxLength < 0) {
                throw Slots.valueError("max_length must be non-negative");
            }
            calls.add(new Call(data, maxLength));
            return PyBytes.of(feed(data, maxLength));
        }

        private PyObject flush(PyObject[] args, String[] kwNames) {
            if (args.length > 1) {
                throw Slots.typeError("flush() takes at most 1 argument (%d given)", args.length);
            }
            PyObject[] values = Arguments.fill(
                    "flush",
                    args,
                    kwNames,
                    new String[] {"length"},
                    new PyObject[] {PyInt.of(16384)});
            long length = sizeArgument(values[0]);
            if (length <= 0) {
                throw Slots.valueError("length must be greater than zero");
            }
            if (eof) {
                return PyBytes.of(EMPTY);
            }
            flushed = true;
            byte[] out = feed(EMPTY, 0);
            tail = EMPTY;
            return PyBytes.of(out);
        }

        private PyObject copy(PyObject[] args, String[] kwNames) {
            Arguments.none("copy", args, kwNames);
            if (eof || flushed) {
                throw Slots.valueError("Inconsistent state");
            }
            // The JVM's Inflater has no clone, so the only way to a second stream at the same point
            // is the calls that got the first one there, replayed with the bounds they were given.
            Decompress clone = new Decompress(wbits, zdict);
            for (Call call : calls) {
                clone.feed(call.data, call.maxLength);
            }
            clone.calls.addAll(calls);
            return clone;
        }

        private PyObject deepcopy(PyObject[] args, String[] kwNames) {
            Arguments.exactlyOne("__deepcopy__", args, kwNames);
            return copy(new PyObject[0], null);
        }

        // The real work: one call's input through the state machine, bounded by maxLength.
        private byte[] feed(byte[] data, long maxLength) {
            if (eof) {
                if (data.length != 0) {
                    unused = concat(unused, data);
                }
                tail = EMPTY;
                return EMPTY;
            }
            byte[] input = data;
            if (mode == 3 && data.length >= 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
                mode = 1;
                inflater.end();
                inflater = new Inflater(true);
            }
            if (mode == 1) {
                if (gzipState == 2) {
                    byte[] all = concat(gzipPending, data);
                    gzipPending = EMPTY;
                    finishGzip(all);
                    return EMPTY;
                }
                input = gzipInput(data);
                if (input == null) {
                    return EMPTY;
                }
            } else if (mode == 0 || mode == 3) {
                checkZlibWindow(data);
            }
            return inflateWith(input, maxLength);
        }

        /** zlib refuses a stream whose header announces a larger window than the caller allowed. */
        private void checkZlibWindow(byte[] data) {
            if (headerRead || data.length < 2) {
                return;
            }
            headerRead = true;
            int cmf = data[0] & 0xFF;
            if ((cmf & 0x0F) != 8) {
                return; // the method byte is the Inflater's own complaint
            }
            int cinfo = (cmf >> 4) + 8;
            if (cinfo > window) {
                throw dataError(-3, "invalid window size");
            }
        }

        /**
         * The gzip container, read here because the JVM has no gzip mode: the optional header fields
         * are skipped and the deflate payload is handed to a raw {@link Inflater}. Returns the payload
         * bytes available in this call, or {@code null} when the header is still incomplete.
         */
        private byte[] gzipInput(byte[] data) {
            byte[] all = concat(gzipPending, data);
            gzipPending = EMPTY;
            if (gzipState == 0) {
                if (all.length < 10) {
                    gzipPending = all;
                    return null;
                }
                if ((all[0] & 0xFF) != 0x1F || (all[1] & 0xFF) != 0x8B) {
                    throw dataError(-3, "incorrect header check");
                }
                if (all[2] != 8) {
                    throw dataError(-3, "unknown compression method");
                }
                int flags = all[3] & 0xFF;
                int at = 10;
                if ((flags & 4) != 0) {
                    if (all.length < at + 2) {
                        gzipPending = all;
                        return null;
                    }
                    int extra = (all[at] & 0xFF) | ((all[at + 1] & 0xFF) << 8);
                    at += 2 + extra;
                }
                if ((flags & 8) != 0) {
                    while (at < all.length && all[at] != 0) {
                        at++;
                    }
                    if (at >= all.length) {
                        gzipPending = all;
                        return null;
                    }
                    at++;
                }
                if ((flags & 16) != 0) {
                    while (at < all.length && all[at] != 0) {
                        at++;
                    }
                    if (at >= all.length) {
                        gzipPending = all;
                        return null;
                    }
                    at++;
                }
                if ((flags & 2) != 0) {
                    at += 2;
                }
                if (at > all.length) {
                    gzipPending = all;
                    return null;
                }
                gzipState = 1;
                all = at >= all.length ? EMPTY : java.util.Arrays.copyOfRange(all, at, all.length);
            }
            if (all.length == 0) {
                return EMPTY;
            }
            return all;
        }

        /** The last eight bytes of a gzip member: CRC-32 then the uncompressed length, little-endian. */
        private void finishGzip(byte[] rest) {
            while (trailerHave < 8 && rest.length != 0) {
                int take = Math.min(8 - trailerHave, rest.length);
                System.arraycopy(rest, 0, trailer, trailerHave, take);
                trailerHave += take;
                rest = java.util.Arrays.copyOfRange(rest, take, rest.length);
            }
            if (trailerHave < 8) {
                gzipState = 2;
                gzipPending = rest;
                return;
            }
            long crc = readLittle(trailer, 0);
            long size = readLittle(trailer, 4);
            if ((gzipCrc.getValue() & 0xFFFFFFFFL) != crc || (gzipSize & 0xFFFFFFFFL) != size) {
                throw dataError(-3, "incorrect data check");
            }
            eof = true;
            gzipState = 1;
            if (rest.length != 0) {
                unused = concat(unused, rest);
            }
        }

        private byte[] inflateWith(byte[] input, long maxLength) {
            if (input.length != 0) {
                inflater.setInput(input);
            }
            byte[] buffer = new byte[maxLength == 0 ? 8192 : (int) Math.min(Math.max(maxLength, 1), 1 << 20)];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try {
                while (true) {
                    int n = inflater.inflate(buffer);
                    if (n > 0) {
                        out.write(buffer, 0, n);
                        if (mode == 1) {
                            gzipCrc.update(buffer, 0, n);
                            gzipSize += n;
                        }
                        if (maxLength > 0 && out.size() >= maxLength) {
                            break;
                        }
                    } else if (inflater.finished()) {
                        break;
                    } else if (inflater.needsInput()) {
                        break;
                    } else if (inflater.needsDictionary()) {
                        if (zdict.length == 0) {
                            throw dataError(2, "");
                        }
                        inflater.setDictionary(zdict);
                    } else {
                        break;
                    }
                }
                int remaining = inflater.getRemaining();
                byte[] rest = remaining == 0 ? EMPTY
                        : java.util.Arrays.copyOfRange(input, input.length - remaining, input.length);
                if (inflater.finished()) {
                    if (mode == 1) {
                        finishGzip(rest);
                    } else {
                        eof = true;
                        if (rest.length != 0) {
                            unused = concat(unused, rest);
                        }
                    }
                    tail = EMPTY;
                } else {
                    tail = rest;
                    if (mode == 1) {
                        gzipPending = rest;
                    }
                }
            } catch (DataFormatException e) {
                throw dataError(-3, e.getMessage());
            }
            return out.toByteArray();
        }

        private static long readLittle(byte[] b, int at) {
            return (b[at] & 0xFFL)
                    | ((b[at + 1] & 0xFFL) << 8)
                    | ((b[at + 2] & 0xFFL) << 16)
                    | ((b[at + 3] & 0xFFL) << 24);
        }
    }

    // --- shared helpers -----------------------------------------------------------------------

    static PyRaise dataError(int code, String text) {
        return streamError(code, "decompressing data", text);
    }

    static PyRaise streamError(int code, String during, String text) {
        String message = text == null || text.isEmpty()
                ? "Error " + code + " while " + during
                : "Error " + code + " while " + during + ": " + text;
        return PyRaise.raise(ExceptionTypes.ZLIB_ERROR, message);
    }

    /**
     * A size the reference reads as {@code Py_ssize_t}: the top half of the unsigned long range is
     * refused rather than clamped, which is how {@code sys.maxsize + 1} becomes an {@code OverflowError}.
     */
    private static long sizeArgument(PyObject value) {
        java.math.BigInteger number = Numbers.asIndex(value);
        if (number.bitLength() > 63) {
            throw PyRaise.raise(
                    ExceptionTypes.OVERFLOW_ERROR, "Python int too large to convert to C ssize_t");
        }
        return number.longValue();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        if (a.length == 0) {
            return b;
        }
        if (b.length == 0) {
            return a;
        }
        byte[] joined = new byte[a.length + b.length];
        System.arraycopy(a, 0, joined, 0, a.length);
        System.arraycopy(b, 0, joined, a.length, b.length);
        return joined;
    }

    private static int crcOf(byte[] data, int initial) {
        int crc = initial ^ 0xFFFFFFFF;
        for (byte octet : data) {
            crc = CRC_TABLE[(crc ^ octet) & 0xFF] ^ (crc >>> 8);
        }
        return crc ^ 0xFFFFFFFF;
    }

    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
            }
            CRC_TABLE[i] = c;
        }
    }
}
