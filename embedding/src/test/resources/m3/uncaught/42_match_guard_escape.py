def decide(value):
    raise RuntimeError("the guard refused")


def run(subject):
    match subject:
        case {"a": found} if decide(found):
            return found
        case _:
            return "unmatched"


run({"a": 1})
