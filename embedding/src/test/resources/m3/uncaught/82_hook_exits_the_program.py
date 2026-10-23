import sys


def quitter(kind, value, tb):
    print("the hook is leaving")
    sys.exit(2)


sys.excepthook = quitter
raise ValueError("never reported")
