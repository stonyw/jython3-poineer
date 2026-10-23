import sys

# A traceback handed to the display goes onto the value, because otherwise nothing would show it —
# but only where the value carries none of its own.
def inner():
    try:
        raise ValueError("carried")
    except ValueError:
        return sys.exc_info()[2]


handed = inner()
fresh = ValueError("handed over")
sys.excepthook(ValueError, fresh, handed)
print("the value kept it:", fresh.__traceback__ is handed)

carried = KeyError("its own")
try:
    raise carried
except KeyError:
    own = sys.exc_info()[2]
sys.excepthook(KeyError, carried, handed)
print("the value kept its own:", carried.__traceback__ is own)

raise fresh
