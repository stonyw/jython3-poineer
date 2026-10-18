ints = (-7, -1, 0, 1, 3, 7, 2**70, -(2**70), 10**40)
for a in ints:
    for b in (-3, -1, 0, 1, 3):
        print(
            a,
            b,
            a + b,
            a - b,
            a * b,
            a // b if b else "-",
            a % b if b else "-",
            divmod(a, b) if b else "-",
            a & b,
            a | b,
            a ^ b,
            ~a if abs(a) < 100 else "-",
            -a,
            abs(a),
            a << b if 0 <= b < 8 else "-",
            a >> b if 0 <= b < 8 else "-",
            a**b if (b >= 0 or (a and abs(a) < 100)) else "-",
        )
print(7 // 2, -7 // 2, 7 % -2, -7 % 2, 2**100, (1 << 200) // 3, 10**20 % 7)
print(int("ff", 16), int("077", 8), int("1010", 2), int("  -42 \n"))
print(bin(255), oct(255), hex(255), hex(-255), bin(0), oct(0), hex(0))
print(int(3.9), int(-3.9), int(True), int(False), int(7 / 2))
print(pow(2, 10), pow(3, 3, 4), pow(2, -1, 5), pow(2.0, 0.5))
print(1 / 3, 7 / 2, -7 / 2, 7 / -2, 0.1 + 0.2, 10**40 / 10**38)
