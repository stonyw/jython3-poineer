"""The order a handler, its else, its finally and a bare raise run in."""

order = []


def holds_across_finally():
    try:
        order.append("try returns")
        return "returned from try"
    finally:
        order.append("finally after a return")


def reraise_after_cleaning():
    try:
        raise ValueError("outward")
    except ValueError:
        raise
    finally:
        order.append("inner finally")


def raise_inside_handler():
    try:
        try:
            raise ValueError("first")
        except ValueError:
            order.append("handler")
            raise TypeError("second")
        finally:
            order.append("finally")
    except TypeError as error:
        order.append("outer " + str(error))


def nested_finally(depth):
    try:
        if depth:
            nested_finally(depth - 1)
        else:
            raise RuntimeError("bottom")
    finally:
        order.append("level " + str(depth))


for function in (reraise_after_cleaning, raise_inside_handler):
    try:
        function()
    except BaseException as error:
        order.append("escaped " + type(error).__name__ + " " + str(error))

print(order)
print(holds_across_finally())

order.clear()
try:
    nested_finally(2)
except RuntimeError as error:
    order.append("caught " + str(error))
print(order)


def guarded_call(n):
    if n:
        raise RuntimeError("from a callee")


def escapes_a_call():
    guarded_call(1)


try:
    escapes_a_call()
except RuntimeError as error:
    print("caught at the caller:", str(error))
