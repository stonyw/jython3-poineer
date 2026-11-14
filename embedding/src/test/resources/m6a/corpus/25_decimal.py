import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

# Comparison method, noted here because the two halves cannot be compared the same way:
#
#   `decimal` is the module the program names; in this runtime it *is* `_pydecimal`, because
#   `decimal.py` replaces `sys.modules["decimal"]` with the pure-Python module whenever the C
#   accelerator is missing. The reference answers the C implementation for that name. So each
#   block below runs twice with a tag — "C" for `decimal`, "P" for `_pydecimal` — and the bytes
#   are compared line for line: reference C against this runtime's C, reference P against
#   this runtime's P. On this runtime the two blocks are identical by construction; on the
#   reference they exercise two implementations that must agree.
import copy
import decimal
import pickle

import _pydecimal


def digest(tag, module):
    Decimal = module.Decimal
    Context = module.Context

    print(tag, "one", str(Decimal("1.1")), repr(Decimal("1.1")))
    print(tag, "add", str(Decimal("1.1") + Decimal("2.2")))
    print(tag, "sub", str(Decimal("1.0") - Decimal("0.9")))
    print(tag, "mul", str(Decimal("1.5") * Decimal("2")))
    print(tag, "div", str(Decimal(1) / Decimal(8)))
    print(tag, "zero-div", str(Decimal(0) / Decimal(7)))
    print(tag, "neg", str(-Decimal("2.50")))
    print(tag, "abs", str(abs(Decimal("-2.50"))))
    print(tag, "tuple", Decimal("12.30").as_tuple())
    print(tag, "ratio", Decimal("0.375").as_integer_ratio())
    print(tag, "float-ratio", Decimal("0.1").as_integer_ratio())
    print(tag, "from-float", str(Decimal.from_float(0.1)))
    print(tag, "eng", Decimal("1234567").to_eng_string())
    print(tag, "eng-small", Decimal("0.0001234").to_eng_string())
    print(tag, "quantize", str(Decimal("1.2345").quantize(Decimal("0.01"))))
    print(tag, "normalize", str(Decimal("1.200").normalize()))
    print(tag, "sqrt", str(Decimal(2).sqrt()))

    context = Context(prec=9, rounding=module.ROUND_HALF_EVEN, traps=[])
    print(tag, "third", str(context.divide(Decimal(1), Decimal(3))))
    print(tag, "prec", str(context.create_decimal("1.23456789012345")))
    print(tag, "half-up", str(Decimal("2.5").quantize(Decimal("1"), rounding=module.ROUND_HALF_UP)))
    print(tag, "half-even", str(Decimal("2.5").quantize(Decimal("1"))))
    print(tag, "flags", sorted(name.__name__ for name, value in context.flags.items() if value))
    context.clear_flags()
    print(tag, "flags-clear", sorted(name.__name__ for name, value in context.flags.items() if value))

    infinity = context.divide(Decimal(1), Decimal(0))
    print(tag, "infinity", str(infinity), infinity.is_infinite())
    print(tag, "nan", str(Decimal("NaN")), Decimal("NaN").is_nan())
    print(tag, "nan-compare", Decimal("NaN") == Decimal("NaN"))
    print(tag, "compare-total", Decimal("1.0").compare_total(Decimal("1.00")))
    print(tag, "next-plus", str(Decimal("1").next_plus(context)))
    print(tag, "min", str(min(Decimal("1"), Decimal("2"))))

    print(tag, "format-f", format(Decimal("1.2345"), ".2f"))
    print(tag, "format-e", format(Decimal("1234.5"), ".2e"))
    print(tag, "format-g", format(Decimal("1234.5"), ".3g"))
    print(tag, "format-percent", format(Decimal("0.125"), ".1%"))
    print(tag, "format-width", "[%10.2f]" % Decimal("3.14159"))

    with module.localcontext() as local:
        local.prec = 4
        print(tag, "local", str(Decimal(1) / Decimal(3)))
    print(tag, "outer", str(module.getcontext().prec))

    print(tag, "copy", str(copy.copy(Decimal("1.25"))))
    print(tag, "deepcopy", str(copy.deepcopy(Decimal("1.25"))))
    # Pickle finds a class by `__module__` and `__qualname__`: the reference's `_pydecimal.Decimal`
    # names `decimal` while `decimal.Decimal` is the C class there, so the pure-Python pass cannot
    # round-trip a pickle on the reference at all. Asked only of the "C" block, which on this
    # runtime is the same module, keeps both interpreters' byte streams identical.
    if tag == "C":
        print(tag, "pickle", str(pickle.loads(pickle.dumps(Decimal("1.25")))))
        print(tag, "pickle-context", pickle.loads(pickle.dumps(Context(prec=7))).prec)

    print(tag, "hierarchy",
          issubclass(module.InvalidOperation, module.DecimalException),
          issubclass(module.DivisionByZero, module.DecimalException),
          issubclass(module.Overflow, module.Inexact))


for tag, module in (("C", decimal), ("P", _pydecimal)):
    digest(tag, module)
