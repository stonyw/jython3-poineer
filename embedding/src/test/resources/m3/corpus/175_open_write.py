"""M3 slice 106: the writing half of `open`, and the layers the writes pass through.

Each fixture is written by this script; the harness removes the names before every
interpreter starts and after the last one, because a script cannot delete them (this
runtime has no `os` module) and `x` refuses a name an earlier run left behind. The two
halves are kept apart: the text layer counts characters and rewrites '\n', the binary
one counts octets and reports the flags `FileIO` derived rather than the spelling given.
"""


def fails(call):
    try:
        call()
    except Exception as error:
        print(type(error).__name__ + ':', error)


def octets(name):
    with open(name, 'rb') as source:
        return source.read()


# Text `w` truncates, reports the caller's spelling, and counts characters.
with open('w_text.txt', 'w') as text:
    print(text.mode, text.readable(), text.writable(), text.closed)
    print(text.write('one\ntwo\n'))
    print(text.flush())
    print(text.closed)
print(text.closed)
print(octets('w_text.txt'))

with open('w_text.txt', 'w') as shorter:
    print(shorter.write('x'))
print(octets('w_text.txt'))

# `w+` truncates as well, and `truncate` measures the octets the encoder wrote.
with open('w_text.txt', 'w+') as both:
    print(both.mode, both.readable(), both.writable())
    print(both.write('abc'))
    print(both.truncate(1))
print(octets('w_text.txt'))

# `a` appends to what is there rather than truncating it.
with open('w_text.txt', 'a') as appending:
    print(appending.mode)
    print(appending.write(' two'))
print(octets('w_text.txt'))

# `x` creates a name that is absent and refuses one that is not.
with open('x_text.txt', 'x') as created:
    print(created.mode, created.write('fresh'))
print(octets('x_text.txt'))
with open('dup_x.bin', 'wb') as seed:
    seed.write(b'occupied')
fails(lambda: open('dup_x.bin', 'x'))
fails(lambda: open('dup_x.bin', 'x+b'))

# `writelines` is the one writing row that answers None.
with open('lines.txt', 'w') as lines:
    print(lines.writelines(['a', 'b\n']))
print(octets('lines.txt'))

# The binary layers report the flags FileIO derived, not the caller's spelling.
with open('w_bin.bin', 'wb') as raw:
    print(type(raw).__name__, raw.mode, raw.readable(), raw.writable(), raw.seekable())
    print(raw.write(b'0123456789'))
    print(raw.tell())
    raw.seek(3)
    print(raw.write(bytearray(b'abc')))
    print(raw.tell())
print(octets('w_bin.bin'))

with open('w_bin.bin', 'ab') as appending:
    print(type(appending).__name__, appending.mode)
    print(appending.write(b'Z'))
print(octets('w_bin.bin'))

# `r+` neither truncates nor appends: a write lands where the seek put it.
with open('seed_r.bin', 'wb') as seed:
    seed.write(b'abcdef')
with open('seed_r.bin', 'r+b') as both:
    print(type(both).__name__, both.mode)
    print(both.read())
    both.seek(2)
    print(both.write(b'XY'))
    both.seek(0)
    print(both.read())
print(octets('seed_r.bin'))

# `a+` opens at the end, so its first read is empty until it seeks.
with open('seed_a.bin', 'wb') as seed:
    seed.write(b'base')
with open('seed_a.bin', 'a+b') as both:
    print(type(both).__name__, both.mode)
    print(both.read())
    both.seek(0)
    print(both.write(b'X'))
    both.seek(0)
    print(both.read())
print(octets('seed_a.bin'))

# `w+` truncates what an earlier stream left, and `truncate` cuts where it is told.
with open('seed_w.bin', 'wb') as seed:
    seed.write(b'longer content')
with open('seed_w.bin', 'w+b') as both:
    print(type(both).__name__, both.mode)
    print(both.write(b'abcdef'))
    print(both.truncate(3))
    print(both.tell())
print(octets('seed_w.bin'))

with open('brand.bin', 'x+b') as both:
    print(type(both).__name__, both.mode, both.write(b'hi'))
    both.seek(0)
    print(both.read())
print(octets('brand.bin'))

# buffering=0 hands the descriptor itself to a binary caller.
with open('bare.bin', 'wb', buffering=0) as bare:
    print(type(bare).__name__, bare.mode)
    print(bare.write(b'raw'))
    print(bare.tell())
    bare.seek(1)
    print(bare.write(b'X'))
print(octets('bare.bin'))

# buffering=1 is the text layer's line buffering, and a size above one is accepted.
with open('lined.txt', 'w', buffering=1) as lined:
    print(lined.line_buffering, lined.write_through)
    lined.write('one')
    print(len(octets('lined.txt')))
    lined.write('\n')
    print(len(octets('lined.txt')))
with open('sized.txt', 'w', buffering=100) as sized:
    print(sized.line_buffering)
fails(lambda: open('sized.txt', 'w', buffering=0))

# The encoder the mode string chose is the one the octets show.
with open('latin.txt', 'w', encoding='latin-1') as latin:
    print(latin.encoding)
    latin.write('\u00e9\n')
print(octets('latin.txt'))
with open('utf8.txt', 'w', encoding='utf-8') as utf8:
    print(utf8.encoding)
    utf8.write('\u00e9\n')
print(octets('utf8.txt'))
fails(lambda: open('ascii.txt', 'w', encoding='ascii').write('\u00e9'))

# newline rewrites only '\n': None is the platform separator, '' and '\n' keep it, and
# the other two spell it.
with open('nl_default.txt', 'w') as default:
    default.write('a\nb\n')
with open('nl_keep.txt', 'w', newline='') as keep:
    keep.write('a\nb\n')
with open('nl_lf.txt', 'w', newline='\n') as lf:
    lf.write('a\nb\n')
with open('nl_cr.txt', 'w', newline='\r') as cr:
    cr.write('a\nb\n')
with open('nl_crlf.txt', 'w', newline='\r\n') as crlf:
    crlf.write('a\nb\n')
for name in ['nl_default.txt', 'nl_keep.txt', 'nl_lf.txt', 'nl_cr.txt', 'nl_crlf.txt']:
    print(name, octets(name))

# close, flush and the context manager on a binary stream: flush returns None, close is
# idempotent, and both halves refuse a closed stream with their own sentences.
raw = open('closed.bin', 'wb')
print(raw.__enter__() is raw)
print(raw.write(b'payload'))
print(raw.flush())
print(raw.closed)
raw.close()
print(raw.closed)
print(raw.close())
fails(lambda: raw.write(b'x'))
fails(lambda: raw.flush())
print(raw.__exit__(None, None, None))

# The text layer refuses a closed stream with the io module's sentence, and the other
# direction with `not writable`.
text = open('closed.txt', 'w')
print(text.write('payload'))
print(text.flush())
text.close()
fails(lambda: text.write('x'))
fails(lambda: text.flush())
with open('pairs.txt', 'wb') as seed:
    seed.write(b'pair')
fails(lambda: open('pairs.txt', 'r').write('x'))
fails(lambda: open('pairs.txt', 'rb').write(b'x'))
fails(lambda: open('pairs.txt', 'w').read())
fails(lambda: open('pairs.txt', 'wb').read())
fails(lambda: open('pairs.txt', 'w').write(1))
