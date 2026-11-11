"""bytearray's last segment: int() over octets, percent formatting, and the four dunders.

int() reads a run through the same ASCII door as a bytes: the six blanks are stripped and nothing
else is, no Unicode digit is folded into another, and the refusal quotes the run's own repr cut at
200 octets. The percent operator is PEP 461's, where a tuple is a list of positions and anything
else is a single position that a keyed conversion refills. __buffer__ and __release_buffer__ are
the two ends of a protocol whose only user, memoryview, is not built in this runtime yet, so the
calls here are the refusals. __reduce__ and __reduce_ex__ rebuild the run from its octets, carried
as text below protocol 3 and as bytes from there on.
"""


def attempt(label, call):
    try:
        print(label, "->", repr(call()))
    except Exception as refusal:
        print(label, "->", type(refusal).__name__, str(refusal))


# --- int() reads a run as it reads a bytes --------------------------------------------------

print("int-bytes", int(b"12"), int(b"  42  "), int(b"+7"), int(b"-7"))
print("int-tabs", int(b" \t\n\v\f\r12 \t\n"))
print("int-bytearray", int(bytearray(b"12")), int(bytearray(b" -7 ")))
print("int-underscore", int(b"1_0"))
print("int-base-16", int(b"0x1f", 16), int(b"1f", 16))
print("int-base-0", int(b"0b101", 0), int(b"0o17", 0), int(b"0x1f", 0))
print("int-base-0-sign", int(b"+0x1f", 0))
print("int-base-2", int(b"101", 2))

attempt("int-ff", lambda: int(b"\xff"))
attempt("int-nbsp", lambda: int(b"\xa012"))
attempt("int-indent", lambda: int(b"+ 7"))
attempt("int-fs", lambda: int(b"\x1c12"))
attempt("int-empty", lambda: int(b""))
attempt("int-blank", lambda: int(b"   "))
attempt("int-newline", lambda: int(b"\n"))
attempt("int-bytearray-ff", lambda: int(bytearray(b"\xff")))
attempt("int-long-literal", lambda: int(b"\xff" * 100))
attempt("int-longer-literal", lambda: int(bytearray(b"\xff" * 300)))
attempt("int-none-base", lambda: int(b"12", None))
attempt("int-base-big", lambda: int(b"1", 2 ** 70))
attempt("int-base-1", lambda: int(b"1", 1))
attempt("int-base-37", lambda: int(b"1", 37))

# --- float() reads a run too ----------------------------------------------------------------

print("float-bytearray", float(bytearray(b"1.5")), float(bytearray(b" 1.5 ")))
print("float-underscore", float(bytearray(b"1_0.5")))
print("float-bytes", float(b"1.5"))

attempt("float-bytearray-bad", lambda: float(bytearray(b"x")))
attempt("float-bytearray-empty", lambda: float(bytearray(b"")))
attempt("float-bytes-bad", lambda: float(b"x"))

# --- PEP 461: bytes and bytearray formatting ------------------------------------------------

print("mod-runs", b"%s|%b|%a" % (b"x", bytearray(b"y"), "é"))
print("mod-bytearray", bytearray(b"%s") % b"x", type(bytearray(b"%s") % b"x").__name__)
print("mod-bytes-of-bytearray", b"%s" % bytearray(b"x"))
print("mod-percent", b"100%%", bytearray(b"a%%b"))
print("mod-widths", b"[%5s]" % b"ab", b"[%-5s]" % b"ab", b"[%05d]" % 42)
print("mod-signs", b"[%+d]" % 42, b"[% d]" % 42, b"[%05.1f]" % 2.25)
print("mod-star", b"[%*d]" % (5, 42), b"[%-*d]" % (5, 42), b"[%*d]" % (-5, 42))
print("mod-star-bool", b"[%*d]" % (True, 5))
print("mod-precision", b"[%.2s]" % b"abcd", b"[%.*s]" % (2, b"abcd"))
print("mod-precision-negative", b"[%.*s]" % (-3, b"abcd"))
print("mod-char", b"%c%c%c" % (65, b"b", bytearray(b"c")))
print("mod-char-bool", b"%c" % True)
print("mod-integers", b"%d %i %u" % (1.9, -2, -1))
print("mod-octal-hex", b"%o %x %X" % (8, 255, 255))
print("mod-floats", b"%f %F %e %E %g %G" % (1.5, 1.5, 1.5, 1.5, 1.5, 1.5))
print("mod-alternate", b"%#x %#o" % (255, 8))
print("mod-repr-of-runs", b"%r %a" % (bytearray(b"ab"), b"ab"))
print("mod-keyed", b"%(k)s %(j)r" % {b"k": b"v", b"j": "é"})
print("mod-keyed-run", bytearray(b"%(k)s") % {b"k": b"v"})

attempt("mod-not-all", lambda: b"%s" % (b"a", b"b"))
attempt("mod-not-all-run", lambda: bytearray(b"%s") % (b"a", b"b"))
attempt("mod-not-enough", lambda: b"%s %s" % (b"a",))
attempt("mod-not-enough-empty", lambda: b"%q" % ())
attempt("mod-percent-arg", lambda: b"%%" % b"x")
attempt("mod-incomplete", lambda: b"%" % ())
attempt("mod-incomplete-key", lambda: b"%(k" % {})
attempt("mod-keyed-missing", lambda: b"%(k)q" % {})
attempt("mod-mapping-required", lambda: b"%(k)s" % b"v")
attempt("mod-mapping-run", lambda: b"%(k)s" % bytearray(b"v"))
attempt("mod-keyed-str-key", lambda: b"%(k)s" % {"k": b"v"})
attempt("mod-unknown", lambda: b"%q" % (b"x",))
attempt("mod-high-char", lambda: b"%\x80" % (b"x",))
attempt("mod-width-percent", lambda: b"%5%" % (b"x",))
attempt("mod-star-str", lambda: b"%*q" % ("a",))
attempt("mod-star-big", lambda: b"%*q" % (2 ** 70,))
attempt("mod-star-min", lambda: b"%*d" % (-2 ** 63, 1))
attempt("mod-width-too-big", lambda: b"%99999999999999999999999q" % ())
attempt("mod-prec-too-big", lambda: b"%.2147483648q" % ())
attempt("mod-prec-star-big", lambda: b"%.*q" % (2 ** 40,))
attempt("mod-c-bytes", lambda: b"%c" % b"ab")
attempt("mod-c-bytearray", lambda: bytearray(b"%c") % bytearray(b"ab"))
attempt("mod-c-str", lambda: b"%c" % "a")
attempt("mod-c-300", lambda: b"%c" % 300)
attempt("mod-c-negative", lambda: b"%c" % -1)
attempt("mod-c-huge", lambda: b"%c" % (2 ** 70))
attempt("mod-s-dict", lambda: b"%s" % {})
attempt("mod-b-int", lambda: b"%b" % 1)
attempt("mod-d-bytes", lambda: b"%d" % b"1")
attempt("mod-d-none", lambda: b"%d" % None)
attempt("mod-x-float", lambda: b"%x" % 1.5)
attempt("mod-f-str", lambda: b"%f" % "a")

# --- __mod__ and __rmod__ file one row per half ---------------------------------------------

print("dunder-mod", bytearray.__mod__(bytearray(b"%s"), b"x"))
print("dunder-mod-type", type(bytearray.__mod__(bytearray(b"%s"), b"x")).__name__)
print("dunder-mod-bytes", bytes.__mod__(b"%s", bytearray(b"x")))
print("dunder-rmod", bytearray.__rmod__(bytearray(b"%d"), bytearray(b"%s")))
print("dunder-rmod-type", type(bytearray.__rmod__(bytearray(b"%d"), bytearray(b"%s"))).__name__)
print("dunder-rmod-bytes", bytearray.__rmod__(bytearray(b"%s"), b"x"))
print("dunder-rmod-str", bytearray.__rmod__(bytearray(b"%s"), "%s"))
print("bytes-rmod", bytes.__rmod__(b"%d", b"%s"))
print("bytes-rmod-run", bytes.__rmod__(b"%s", bytearray(b"x")))

# --- the two ends of the buffer protocol, which only memoryview could use -------------------

attempt("buffer-bytes-readonly", lambda: bytes.__buffer__(b"ab", 1))
attempt("buffer-str-flags", lambda: bytearray(b"ab").__buffer__("x"))
attempt("buffer-big-flags", lambda: bytearray(b"ab").__buffer__(2 ** 70))
attempt("buffer-huge-flags", lambda: bytearray(b"ab").__buffer__(2 ** 40))
attempt("buffer-no-args", lambda: bytearray(b"ab").__buffer__())
attempt("buffer-two-args", lambda: bytearray(b"ab").__buffer__(0, 1))

attempt("release-none", lambda: bytearray(b"ab").__release_buffer__(None))
attempt("release-run", lambda: bytearray(b"ab").__release_buffer__(bytearray(b"ab")))
attempt("release-no-args", lambda: bytearray(b"ab").__release_buffer__())
attempt("release-keyword", lambda: bytearray(b"ab").__release_buffer__(buffer=None))

# --- pickle's two rows: text below protocol 3, bytes from there on --------------------------

print("reduce", bytearray(b"ab").__reduce__())
print("reduce-empty", bytearray().__reduce__())
print("reduce-ex-default", bytearray(b"ab").__reduce_ex__())
print("reduce-ex-2", bytearray(b"ab").__reduce_ex__(2))
print("reduce-ex-3", bytearray(b"ab").__reduce_ex__(3))
print("reduce-ex-4", bytearray(b"ab").__reduce_ex__(4))
print("reduce-ex-negative", bytearray(b"ab").__reduce_ex__(-1))
print("reduce-empty-3", bytearray().__reduce_ex__(3))
print("reduce-nonascii", bytearray(b"\xff").__reduce_ex__(0), bytearray(b"\xff").__reduce_ex__(3))

attempt("reduce-arg", lambda: bytearray(b"ab").__reduce__(1))
attempt("reduce-keyword", lambda: bytearray(b"ab").__reduce__(x=1))
attempt("reduce-ex-two", lambda: bytearray(b"ab").__reduce_ex__(2, 3))
attempt("reduce-ex-three", lambda: bytearray(b"ab").__reduce_ex__(2, 3, 4))
attempt("reduce-ex-keyword", lambda: bytearray(b"ab").__reduce_ex__(proto=2))
attempt("reduce-ex-str", lambda: bytearray(b"ab").__reduce_ex__("x"))
attempt("reduce-ex-none", lambda: bytearray(b"ab").__reduce_ex__(None))

# --- the book, name by name and in order ----------------------------------------------------

names = list(bytearray.__dict__)
print("dict-length", len(names))
print("dict-order", names)
print("dict-hash", bytearray.__dict__["__hash__"])

for name in ("__buffer__", "__release_buffer__", "__mod__", "__rmod__", "__reduce__", "__reduce_ex__"):
    member = bytearray.__dict__[name]
    print("member", name, member.__text_signature__, repr(member.__doc__))
