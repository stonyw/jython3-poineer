# SystemExit decides a status before the report ever opens its mouth.
import sys

def bye():
    sys.exit(7)

print('leaving')
bye()
