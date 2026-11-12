"""`unicodedata`'s four normal forms, and the refusals around them.

The module is what `test.support.os_helper` reaches for on `darwin`, so the corpus asks
for the same two answers it does: a composed character decomposed, and the same character
recomposed. `is_normalized` is the same question without building the string, and the
error paths are the ones a caller sees when the form is misspelled or the argument is not
text.
"""

import unicodedata

print(unicodedata.unidata_version)
print(repr(unicodedata.normalize('NFD', '\u0258\u0141\u011f')))
print(repr(unicodedata.normalize('NFC', 'e\u0301')))
print(unicodedata.is_normalized('NFD', 'e\u0301'))
print(unicodedata.is_normalized('NFC', 'e\u0301'))
print(unicodedata.normalize('NFKD', '\ufb01'))
print(unicodedata.normalize('NFKC', '\ufb01'))

try:
    unicodedata.normalize('BAD', 'a')
except ValueError as error:
    print(type(error).__name__, error)

try:
    unicodedata.normalize(1, 'a')
except TypeError as error:
    print(type(error).__name__, error)

try:
    unicodedata.normalize('NFC', 1)
except TypeError as error:
    print(type(error).__name__, error)
