# A local the frame never reached: the error about it names no variable at all, so a builtin one letter
# away is not offered.
def work():
    return prin
    prin = 1


work()
