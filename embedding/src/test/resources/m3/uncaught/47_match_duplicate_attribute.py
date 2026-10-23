class Args:
    __match_args__ = ("first", "second")

    def __init__(self, first, second):
        self.first = first
        self.second = second


def run(subject):
    match subject:
        case Args(captured, first=other):
            return (captured, other)
        case _:
            return "unmatched"


run(Args(1, 2))
