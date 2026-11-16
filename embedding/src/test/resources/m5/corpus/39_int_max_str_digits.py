# The decimal int/str digit limit and both directions it bounds (M5 slice 75).
#
# `sys.set_int_max_str_digits` replaces the limit that `long_to_decimal_string_internal` and
# `_PyLong_FromString` read; zero disables it, and anything else below 640 is refused.  The two
# sentences differ: converting *to* text names the limit only, while reading *from* text can also
# say how many digits the value had.

import sys

show = print


def attempt(tag, action):
    try:
        value = action()
    except Exception as exc:
        show(tag, type(exc).__name__, exc)
    else:
        show(tag, "ok", value)


show("default", sys.get_int_max_str_digits(), sys.flags.int_max_str_digits)

for value in (639, 640, 0, -1, 2147483648, 2147483647):
    attempt("set %d" % value, lambda v=value: sys.set_int_max_str_digits(v))
show("after", sys.get_int_max_str_digits(), sys.flags.int_max_str_digits)

# A limit low enough to reach with a value small in every other way.
sys.set_int_max_str_digits(640)
big = 10 ** 700

attempt("str", lambda: str(big))
attempt("repr", lambda: repr(big))
attempt("fstring", lambda: f"{big}")
attempt("fstring-d", lambda: f"{big:d}")
attempt("format-d", lambda: format(big, "d"))
attempt("percent-d", lambda: "%d" % big)
attempt("hex", lambda: hex(big)[:10])
attempt("int-str", lambda: int("1" * 700))
attempt("int-str-base10", lambda: int("1" * 700, 10))
attempt("int-bytes", lambda: int(b"1" * 700))
attempt("int-str-base16", lambda: int("1" * 700, 16))
attempt("int-hex-prefix", lambda: int("0x" + "f" * 700, 0))

# The limit is inclusive: 4300 digits convert, 4301 do not.
sys.set_int_max_str_digits(4300)
show("at-limit", len(str(10 ** 4300)))
attempt("over-limit", lambda: str(10 ** 4300))
attempt("parse-at-limit", lambda: len(str(int("1" * 4300))))
attempt("parse-over-limit", lambda: int("1" * 4301))

# Zero turns the check off in both directions.
sys.set_int_max_str_digits(0)
show("unlimited", len(str(10 ** 5000)), len(str(int("1" * 5000))))
sys.set_int_max_str_digits(4300)
