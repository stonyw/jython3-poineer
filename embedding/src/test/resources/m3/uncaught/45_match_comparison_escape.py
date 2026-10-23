class Compare:
    def __eq__(self, other):
        raise RuntimeError("the comparison refused")


def run(subject):
    match subject:
        case [1, second]:
            return second
        case _:
            return "unmatched"


run([Compare(), 2])
