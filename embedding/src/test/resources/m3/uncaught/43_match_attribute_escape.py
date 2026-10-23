class Read:
    @property
    def value(self):
        raise RuntimeError("the attribute refused")


def run(subject):
    match subject:
        case Read(value=found):
            return found
        case _:
            return "unmatched"


run(Read())
