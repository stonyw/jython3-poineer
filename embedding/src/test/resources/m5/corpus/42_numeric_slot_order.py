# Numeric binary dispatch follows `binary_op1`'s slot order (M5 slice 75).
#
# Each of int/float/complex files its own C slot and declines partners it cannot read, so a
# subclass's reflected method gets its turn.  The reflected slot is invoked with the expression's
# operand order (`slotw(v, w)`), which is why `(2.5).__rtruediv__(1)` is `1 / 2.5`.

class MyFloat(float):
    def __rtruediv__(self, other):
        return ("rtruediv", other, float(self))

    def __rsub__(self, other):
        return ("rsub", other, float(self))


class Plain(float):
    pass


def attempt(tag, action):
    try:
        print(tag, action())
    except Exception as exc:
        print(tag, type(exc).__name__, exc)


attempt("int-div-myfloat", lambda: 1 / MyFloat(2.5))
attempt("int-sub-myfloat", lambda: 1 - MyFloat(2.5))
attempt("int-div-plain-subclass", lambda: 1 / Plain(2.5))
attempt("int-plus-complex", lambda: 1 + 2j)
attempt("float-plus-complex", lambda: 2.0 + 1j)
attempt("int-times-float", lambda: 3 * 2.5)
attempt("float-times-int", lambda: 2.5 * 3)
attempt("int-rtruediv-float", lambda: (1).__rtruediv__(2.5))
attempt("float-rtruediv-int", lambda: (2.5).__rtruediv__(1))
attempt("float-rsub-int", lambda: (2.5).__rsub__(1))
attempt("int-rsub-float", lambda: (1).__rsub__(2.5))
attempt("complex-div-int", lambda: (2 + 3j) / 2)
attempt("int-div-complex", lambda: 2 / (2 + 3j))
attempt("bool-add-float", lambda: True + 2.5)
attempt("myfloat-div-int", lambda: MyFloat(2.5) / 1)
attempt("int-floordiv-myfloat", lambda: 7 // MyFloat(2.0))
attempt("myfloat-rfloordiv", lambda: MyFloat(2.0).__rfloordiv__(7))
attempt("huge-int-plus-float", lambda: 10 ** 400 + 1.0)
attempt("huge-int-div-complex", lambda: 10 ** 400 / 1j)
attempt("int-pow-myfloat", lambda: 2 ** MyFloat(3.0))
