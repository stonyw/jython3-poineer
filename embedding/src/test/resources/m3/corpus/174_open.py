"""M3 slice 105: `open`, its argument layer, and the two read paths it can build.

Every refusal below is one `_io.open` raises, printed in the order the reference checks
them: the clinic's conversions, then the mode string, then the file, then the three
questions `TextIOWrapper.__init__` asks about a stream that already exists. The files this
script reads are placed in the working directory by the harness, because this runtime has
no write mode yet.
"""


def fails(call):
    try:
        call()
    except Exception as error:
        print(type(error).__name__ + ':', error)


# The builtin's own face: `_io` files it, `builtins` hands it out.
print(open.__name__, open.__qualname__, open.__module__)
print(open.__text_signature__.split('\n')[0])

# The clinic's conversions and the mode string, in the reference's order.
fails(lambda: open())
fails(lambda: open('open_text.txt', 1))
fails(lambda: open(1.5))
fails(lambda: open('open_text.txt', 'btb'))
fails(lambda: open('open_text.txt', 'bt'))
fails(lambda: open('open_text.txt', 'rw'))
fails(lambda: open('open_text.txt', ''))
fails(lambda: open('open_text.txt', 'rb', encoding='utf-8'))
fails(lambda: open('open_text.txt', 'rb', errors='strict'))
fails(lambda: open('open_text.txt', 'rb', newline='\n'))

# The refusals closefd and opener carry, then the three that wait until the file is open.
fails(lambda: open('open_text.txt', closefd=False))
fails(lambda: open('open_text.txt', buffering=0))
fails(lambda: open('open_text.txt', newline='x'))
fails(lambda: open('open_text.txt', encoding='bogus'))
fails(lambda: open('nosuch.txt'))
fails(lambda: open('.'))
fails(lambda: open('open_text.txt', opener=3))
fails(lambda: open('open_text.txt', opener=lambda name, flags: 'x'))

# A text read: the stream's attributes, its repr, and the endings it meets.
with open('open_text.txt') as text:
    print(text.mode, text.encoding, text.errors, text.line_buffering, text.write_through)
    print(text.name, text.closed)
    print(text)
    print(text.newlines)
    print(text.read())
    print(text.newlines)
    print(text.read())
print(text.closed, text.newlines)

# The same questions a second time, through the keyword form and the spelled mode.
with open(file='open_text.txt', mode='rt') as spelled:
    print(spelled.mode, spelled.readlines())
with open('open_text.txt', 'tr') as reversed_mode:
    print(reversed_mode.mode, list(reversed_mode))

# Which endings are recognised, and whether they are translated.
with open('open_text.txt', newline='') as kept:
    print(repr(kept.read()), kept.newlines)
with open('open_text.txt', newline='\n') as strict:
    print(repr(strict.read()), strict.newlines)
with open('open_text.txt', newline='\r') as returns:
    print(repr(returns.read()), returns.newlines)

# The three fields buffering and the two encoding spellings decide.
with open('open_text.txt', buffering=1) as lined:
    print(lined.line_buffering)
with open('open_text.txt', buffering=True) as truth:
    print(truth.line_buffering)
with open('open_text.txt', buffering=100) as sized:
    print(sized.line_buffering, sized.write_through)
with open('open_text.txt', encoding='UTF8') as spelled:
    print(spelled.encoding)
with open('open_text.txt', encoding='locale') as locale:
    print(locale.encoding)
with open('open_text.txt', errors='') as silent:
    print(repr(silent.errors))

# A binary read: the buffered layer by default, the descriptor alone at buffering=0.
with open('open_bin.dat', 'rb') as raw:
    print(type(raw).__name__, raw.mode, raw.closed)
    print(raw)
    print(raw.read())
    print(raw.read())
print(raw.closed)
with open('open_bin.dat', 'br') as buffered:
    print(type(buffered).__name__, buffered.mode)
with open('open_bin.dat', 'rb', buffering=0) as bare:
    print(type(bare).__name__, bare.mode)
    print(bare)
    print(bare.read())
