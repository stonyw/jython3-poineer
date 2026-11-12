# Four modules that almost every importer below the standard library's top layer asks for:
# `_codecs` (the registry `codecs` is only a wrapper over), `errno` (the numbers a failed system
# call reports), `math`, and `time`. What is pinned here is the part that is deterministic: values
# that do not depend on when the program runs, and the display of a time tuple whose fields are
# given rather than measured.

import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

# --- _codecs: lookup, and the per-codec functions -----------------------------------------


import _codecs

info = _codecs.lookup("utf-8")
print("lookup", type(info).__name__, len(info), info.name)
print("lookup alias", _codecs.lookup("utf8").name, _codecs.lookup("latin-1").name)
print("lookup halves", info.encode("ab"), info.decode(b"ab"))

print("encode", _codecs.encode("abc"), _codecs.encode("abc", "ascii"))
print("decode", _codecs.decode(b"abc"), _codecs.decode(b"abc", "ascii"))
print("utf8 pair", _codecs.utf_8_encode("h\u00e9llo"), _codecs.utf_8_decode(b"h\xc3\xa9llo"))
print("latin pair", _codecs.latin_1_encode("\u00e9"), _codecs.latin_1_decode(b"\xe9"))
print("utf16", _codecs.utf_16_encode("hi"), _codecs.utf_16_decode(b"\xff\xfeh\x00i\x00"))
print("escape", _codecs.unicode_escape_encode("\u00e9"), _codecs.unicode_escape_decode(b"\\xe9"))

try:
    _codecs.lookup("no-such-codec")
except LookupError as exc:
    print("unknown", type(exc).__name__, exc)

try:
    _codecs.encode("abc", "no-such-codec")
except LookupError as exc:
    print("unknown encode", type(exc).__name__, exc)

# --- errno: the numbers, and the number-to-name map ----------------------------------------


import errno

print("errno", errno.ENOENT, errno.EEXIST, errno.EINVAL, errno.EPIPE, errno.ENOTDIR)
print("errno names", errno.errorcode[errno.ENOENT], errno.errorcode[errno.EINVAL])
print("errno alias", errno.EAGAIN == errno.EWOULDBLOCK, errno.errorcode[errno.EAGAIN])
print("errno missing", errno.errorcode.get(999999), errno.errorcode.get(errno.ENOENT))

# --- math: values that are exact in IEEE arithmetic ----------------------------------------


import math

print("constants", math.pi, math.e, math.tau, math.inf, -math.inf)
print("predicates", math.isnan(math.nan), math.isinf(math.inf), math.isfinite(1.0))
print("roots", math.sqrt(4), math.sqrt(2), math.isqrt(17), math.cbrt(27))
print("abs", math.fabs(-3), math.copysign(2, -0.0), math.copysign(2, 0.0))
print("rounding", math.floor(2.7), math.ceil(2.1), math.trunc(-2.7))
print("rounding types", type(math.floor(2.7)).__name__, type(math.trunc(2.7)).__name__)
print("logs", math.log(1), math.log2(8), math.log10(1000), math.log(8, 2), math.log1p(0))
print("exp", math.exp(0), math.exp2(3), math.expm1(0))
print("pow", math.pow(2, 10), math.pow(2, -1))
print("trig exact", math.degrees(math.pi), math.radians(180), math.atan2(0, 1))
print("decomposition", math.frexp(8), math.modf(3.5), math.frexp(0))
print("modular", math.fmod(7, 3), math.remainder(7, 3))
print("geometry", math.hypot(3, 4), math.dist((0, 0), (3, 4)))
print("close", math.isclose(1.0, 1.0 + 1e-12), math.isclose(1.0, 1.1), math.isclose(0, 1e-12))
print("ulp", math.ulp(1.0), math.nextafter(1.0, 2.0))
print("fma", math.fma(2, 3, 4))

print("factorial", math.factorial(0), math.factorial(5), math.factorial(10))
print("combinatorics", math.comb(5, 2), math.comb(3, 4), math.perm(5, 2), math.perm(5))
print("integer", math.gcd(12, 18), math.gcd(), math.lcm(4, 6), math.lcm())
print("prod", math.prod([1, 2, 3]), math.prod([1, 2, 3], start=10), math.prod([]))
print("sums", math.fsum([0.1, 0.2, 0.3]), math.sumprod([1, 2], [3, 4]))

for bad in ("not a number", None):
    try:
        math.sqrt(bad)
    except TypeError as exc:
        print("bad arg", type(exc).__name__, exc)

try:
    math.sqrt(-1)
except ValueError as exc:
    print("domain", type(exc).__name__, exc)

try:
    math.floor(math.inf)
except OverflowError as exc:
    print("overflow", type(exc).__name__, exc)

# --- time: the clocks, and the display of a given time tuple -------------------------------


import time

epoch = time.gmtime(0)
print("epoch", epoch)
print("epoch fields", epoch.tm_year, epoch.tm_mon, epoch.tm_mday, epoch.tm_wday, epoch.tm_yday)
print("epoch index", epoch[0], epoch[8], len(epoch), tuple(epoch))
print("epoch zone", epoch.tm_zone, epoch.tm_gmtoff, epoch.tm_isdst)

later = time.gmtime(1000000000)
print("later", later)
print("later weekday", later.tm_wday, later.tm_yday, later.tm_hour, later.tm_min, later.tm_sec)

given = time.struct_time((2020, 6, 7, 15, 4, 5, 6, 159, 0))
print("given", given)
print("given strftime", time.strftime("%Y-%m-%d %H:%M:%S", given))
print("given calendar", time.strftime("%a %A %b %B %j %U %W %p %I %e", given))
print("given zone", time.strftime("%Z", time.gmtime(0)))
print("asctime", time.asctime(given), time.asctime(time.gmtime(0)))
print("ctime", time.strftime("%c", time.gmtime(0)))

print("clocks", time.time() > 0, time.time_ns() > 0)
print("monotonic", time.monotonic() >= 0, time.monotonic_ns() >= 0)
print("perf", time.perf_counter() >= 0, time.perf_counter_ns() >= 0)
print("process", time.process_time() >= 0, time.thread_time() >= 0)
print("sleep", time.sleep(0))

for name in ("time", "monotonic", "perf_counter", "process_time", "thread_time"):
    info = time.get_clock_info(name)
    print("clock info", name, info.monotonic, info.adjustable, info.implementation)
print("clock ids", time.CLOCK_REALTIME, time.CLOCK_MONOTONIC)
print("clock gettime", time.clock_gettime(time.CLOCK_REALTIME) > 0, time.clock_getres(time.CLOCK_REALTIME))
print("zone", time.timezone, time.daylight, time.tzname, time._STRUCT_TM_ITEMS)

try:
    time.get_clock_info("nope")
except ValueError as exc:
    print("bad clock", type(exc).__name__, exc)

try:
    time.sleep(-1)
except ValueError as exc:
    print("bad sleep", type(exc).__name__, exc)

try:
    time.asctime((1, 2, 3))
except TypeError as exc:
    print("bad tuple", type(exc).__name__, exc)
