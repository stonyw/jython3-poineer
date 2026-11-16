# `pos`/`endpos` are `Py_ssize_t` arguments to every scanning method (M5 slice 75).
#
# The clinic signature is `pos: Py_ssize_t = 0, endpos: Py_ssize_t = sys.maxsize`, so a value
# outside the signed word is refused before the subject is looked at, while an in-range value is
# clipped by `state_init` exactly as the reference clips it: negative to zero, past-the-end to the
# length.  `json.decoder.py_scanstring` is the caller that made this observable.

import re

pattern = re.compile("a+")
text = "baaa"


def attempt(tag, action):
    try:
        value = action()
    except Exception as exc:
        print(tag, type(exc).__name__, exc)
    else:
        if hasattr(value, "__next__"):
            value = list(value)
        print(tag, value)


attempt("match-wide", lambda: pattern.match(text, 2 ** 63))
attempt("match-negative-edge", lambda: pattern.match(text, -(2 ** 63)))
attempt("match-wide-minus-one", lambda: pattern.match(text, 2 ** 63 - 1))
attempt("search-wide", lambda: pattern.search(text, 2 ** 63))
attempt("fullmatch-wide-end", lambda: pattern.fullmatch(text, 0, 2 ** 63))
attempt("findall-wide", lambda: pattern.findall(text, 2 ** 63))
attempt("finditer-wide", lambda: pattern.finditer(text, 2 ** 63))
attempt("match-before-start", lambda: pattern.match(text, -100))
attempt("match-past-end", lambda: pattern.match(text, 100))
attempt("search-reversed", lambda: pattern.search(text, -2, -1))
attempt("match-bad-subject", lambda: pattern.match(1, 2 ** 63))
attempt("match-float", lambda: pattern.match(text, 1.0))

# In range but clipped: pos below zero reads from zero, endpos past the end reads to the end.
attempt("clip-left", lambda: pattern.search(text, -3))
attempt("clip-right", lambda: pattern.search(text, 0, 100))
attempt("clip-both", lambda: pattern.findall(text, -1, 100))
