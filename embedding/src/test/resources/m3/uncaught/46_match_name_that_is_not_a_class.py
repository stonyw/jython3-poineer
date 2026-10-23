def run(subject, matcher):
    match subject:
        case matcher(first):
            return first
        case _:
            return "unmatched"


run([1, 2], [3, 4])
