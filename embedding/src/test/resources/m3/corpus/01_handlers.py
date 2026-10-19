"""Every way a clause can catch, and what each one binds."""


def pick(n):
    if n == 0:
        return "clean"
    if n == 1:
        raise ValueError("one")
    if n == 2:
        raise TypeError("two")
    if n == 3:
        raise KeyError("three")
    if n == 4:
        return 1 / 0
    return "clean"


def guarded(n):
    try:
        result = pick(n)
    except ValueError as error:
        result = "value " + str(error)
    except (TypeError, KeyError) as error:
        result = "tuple " + type(error).__name__
    except BaseException as error:
        result = "wide " + type(error).__name__
    else:
        result = "else " + str(result)
    finally:
        result = result + " + finally"
    return result


for n in range(6):
    print(n, guarded(n))
print("past", guarded(9))


def nested_tuple(n):
    try:
        pick(n)
    except ((TypeError, KeyError), ValueError) as error:
        return "nested " + type(error).__name__
    return "nothing"


def bad_clause(n):
    try:
        pick(n)
    except "text":
        return "unreachable"


def report(label, thunk):
    try:
        print(label, "->", thunk())
    except BaseException as error:
        print(label, "-> escaped", type(error).__name__ + ":", str(error))


for n in (1, 2, 3, 0):
    report("nested tuple clause " + str(n), lambda n=n: nested_tuple(n))
report("bad clause", lambda: bad_clause(1))


def raise_a_string():
    raise "a string"


report("raise a string", raise_a_string)

try:
    raise ValueError("kept")
except ValueError as bound:
    inside = str(bound)
try:
    still = str(bound)
except NameError as error:
    still = "unbound: " + str(error)
print("bound inside:", inside, "| after the clause:", still)
