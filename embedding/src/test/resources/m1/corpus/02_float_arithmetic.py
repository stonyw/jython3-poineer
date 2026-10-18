floats = (-7.5, -0.0, 0.0, 0.1, 2.5, 1e300, 1e-300, float("inf"), float("-inf"), float("nan"))
for a in floats:
    for b in (-3.0, 0.0, 2.0, float("nan"), float("inf")):
        print(
            repr(a),
            repr(b),
            repr(a + b),
            repr(a - b),
            repr(a * b),
            repr(a / b) if b else "-",
            repr(a // b) if b else "-",
            repr(a % b) if b else "-",
            repr(divmod(a, b)) if b else "-",
            repr(-a),
            repr(abs(a)),
            repr(a**2) if abs(a) < 1e100 else "-",
            repr(a < b),
            repr(a == b),
        )
print(repr(1e308 * 10), repr(1e-320 / 1e10), repr(-1e308 * 10))
print(round(2.5), round(3.5), round(-2.675, 2), round(1234, -2), round(0.125, 2), round(7))
print(float("1.5"), float(" -2e3 "), float(3), float(True), float("nan") == float("nan"))
print(complex(1, 2), complex(3, -1) * complex(1, 1), abs(complex(3, 4)), repr(1j), (1 + 2j).real, (1 + 2j).imag)
print(3.9 + 1, 7 / 2 == 3.5, 2 * 3.0, 0.5 * 4, 1 % 0.3, repr(10 / 3 * 3))
print(int(1e10), float(2**70) > 1e30, 2**70 == 2**70, 2**70 == float(2**70))
