class Plain:
    pass


def run(subject):
    match subject:
        case [Plain(first)]:
            return first
        case _:
            return "unmatched"


run([Plain()])
