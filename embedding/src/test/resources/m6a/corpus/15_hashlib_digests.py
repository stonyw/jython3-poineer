"""`hashlib` and `_hashlib`: the digests, their parameters and the interfaces built on them.

Every digest is printed as hex, so a line that agrees with the reference is the same digest and not
merely the same length.  The SHAKE and BLAKE2 constructions are implemented over the JVM's own
primitives where it has one and over the runtime here where it does not; `pbkdf2_hmac` and the
HMAC object are checked through Cpython's `hmac` module, which is a real consumer of `_hashlib`.

`scrypt`, `ripemd160` and `sm3` are deliberately absent: the JVM exposes no primitive for them and
the corpus does not pretend otherwise.
"""
import sys

STD = "/opt/homebrew/Cellar/python@3.14/3.14.10/Frameworks/Python.framework/Versions/3.14/lib/python3.14"
sys.path.insert(0, STD)

import hashlib
import hmac

samples = [b"", b"a", b"abc", b"hello world" * 3, bytes(range(256)) * 2]

for algorithm in ("md5", "sha1", "sha224", "sha256", "sha384", "sha512", "sha512_224",
                  "sha512_256", "sha3_224", "sha3_256", "sha3_384", "sha3_512"):
    prototype = hashlib.new(algorithm)
    print("algo", algorithm, prototype.digest_size, prototype.block_size, prototype.name)
    for sample in samples:
        digest = hashlib.new(algorithm, sample)
        print(" digest", digest.hexdigest())
        split = hashlib.new(algorithm)
        for start in range(0, len(sample), 3):
            split.update(sample[start:start + 3])
        print(" split", split.hexdigest() == digest.hexdigest(),
              split.digest() == digest.digest())
        extended = digest.copy()
        extended.update(b"x")
        print(" copy", extended.hexdigest() != digest.hexdigest(),
              digest.hexdigest())

for algorithm in ("shake_128", "shake_256"):
    prototype = hashlib.new(algorithm)
    print("algo", algorithm, prototype.digest_size, prototype.block_size, prototype.name)
    for sample in samples:
        print(" shake", hashlib.new(algorithm, sample).hexdigest(17))

for algorithm in ("blake2b", "blake2s"):
    constructor = getattr(hashlib, algorithm)
    prototype = constructor()
    print("algo", algorithm, prototype.digest_size, prototype.block_size, prototype.name)
    for sample in samples:
        print(" blake", constructor(sample).hexdigest())
    print(" key", constructor(key=b"key").hexdigest())
    print(" salt", constructor(salt=b"salt").hexdigest())
    print(" person", constructor(person=b"person").hexdigest())
    print(" size", constructor(digest_size=17).hexdigest())
    print(" many", constructor(b"a" * 200, digest_size=17).hexdigest())
print("b2bmix", hashlib.blake2b(b"a" * 300, key=b"k" * 64, salt=b"s" * 16, person=b"p" * 16,
                                fanout=2, depth=3, leaf_size=4096).hexdigest())
print("b2bnode", hashlib.blake2b(node_offset=5, node_depth=2, inner_size=32,
                                 last_node=True).hexdigest())
print("b2smix", hashlib.blake2s(b"a" * 300, key=b"k" * 32, salt=b"s" * 8, person=b"p" * 8,
                                fanout=2, depth=3, leaf_size=4096, node_offset=5,
                                node_depth=2, inner_size=16, last_node=True).hexdigest())

print("pbkdf2", hashlib.pbkdf2_hmac("sha256", b"password", b"salt", 1000).hex())
print("pbkdf2sha1", hashlib.pbkdf2_hmac("sha1", b"p" * 100, b"s", 7, 40).hex())
print("pbkdf2sha512", hashlib.pbkdf2_hmac("sha512", b"p", b"s", 2, 100).hex())

print("hmac", hmac.new(b"key", b"msg", "sha256").hexdigest())
print("hmacfunc", hmac.new(b"key", b"msg", hashlib.sha1).hexdigest())
print("hmacdigest", hmac.digest(b"key", b"msg", "md5").hex())
print("hmaccopy", hmac.new(b"key", b"msg", "sha256").copy().hexdigest())
print("hmaccmp", hmac.compare_digest("abc", "abc"), hmac.compare_digest("abc", "abd"))

print("guaranteed", sorted(hashlib.algorithms_guaranteed))
print("md5", "md5" in hashlib.algorithms_available, hashlib.md5(b"x").hexdigest())
print("usedforsecurity", hashlib.sha256(usedforsecurity=False).hexdigest())
print("new", hashlib.new("sha256", b"abc", usedforsecurity=False).hexdigest())

for call in ("hashlib.new('nope')", "hashlib.new(b'sha256')",
             "hashlib.sha256(b'x', b'y')",
             "hashlib.sha256(data=b'x', string=b'y')",
             "hashlib.sha256(1)", "hashlib.sha256().update('x')",
             "hashlib.shake_128().digest()", "hashlib.sha256().digest(1)",
             "hashlib.blake2b(digest_size=0)", "hashlib.blake2b(digest_size=65)",
             "hashlib.blake2b(key=b'x' * 65)", "hashlib.blake2b(salt=b'x' * 17)",
             "hashlib.pbkdf2_hmac('nope', b'p', b's', 1)"):
    try:
        print(call, "->", repr(eval(call)))
    except Exception as error:
        print(call, "!!", type(error).__name__ + ":", error)
