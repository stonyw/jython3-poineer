"""M5 slice 17: the `_socket` surface `socket.py` imports and the loopback it can drive.

Everything here is local: AF_UNIX socketpairs and a TCP listener on 127.0.0.1, so no name
resolution and no external host is involved. Nothing prints a descriptor number or a port, both
of which differ between runs on purpose; the shape of an address is printed instead.
"""

import _socket

# Address families, socket kinds and levels, spelled as the platform defines them.
print(_socket.AF_UNSPEC, _socket.AF_UNIX, _socket.AF_INET, _socket.AF_INET6)
print(_socket.SOCK_STREAM, _socket.SOCK_DGRAM, _socket.SOCK_RAW, _socket.SOCK_SEQPACKET)
print(_socket.SOL_SOCKET, _socket.SO_REUSEADDR, _socket.SO_KEEPALIVE, _socket.SO_BROADCAST)
print(_socket.SHUT_RD, _socket.SHUT_WR, _socket.SHUT_RDWR)
print(_socket.MSG_OOB, _socket.MSG_PEEK, _socket.MSG_DONTWAIT)
print(_socket.AI_PASSIVE, _socket.AI_CANONNAME, _socket.AI_NUMERICHOST)
print(_socket.IPPROTO_IP, _socket.IPPROTO_TCP, _socket.IPPROTO_UDP, _socket.IPPROTO_IPV6)
print(_socket.IPV6_V6ONLY, _socket.TCP_NODELAY, _socket.TCP_MAXSEG)
print(_socket.INADDR_ANY, _socket.INADDR_BROADCAST, _socket.INADDR_LOOPBACK, _socket.INADDR_NONE)
print(_socket.IPPORT_RESERVED, _socket.SOMAXCONN, _socket.has_ipv6)

# The exception family is four names, two of them aliases of builtins.
print(_socket.error is OSError, _socket.timeout is TimeoutError)
print(_socket.gaierror.__module__, _socket.herror.__module__)
print(_socket.gaierror.__name__, _socket.herror.__name__)
print([base.__name__ for base in _socket.gaierror.__mro__[:3]])
print([base.__name__ for base in _socket.herror.__mro__[:3]])

# Constructor validation: family, kind and protocol are checked before a descriptor exists.
for family, kind, proto in ((9999, 1, 0), (2, 9999, 0), (2, 1, 9999)):
    try:
        _socket.socket(family, kind, proto)
    except OSError as error:
        print(error.errno, error.strerror)

# A socket before it is bound: no address, no peer, and a descriptor that close retires once.
sock = _socket.socket()
print(sock.family, sock.type, sock.proto)
print(sock.getsockname())
print("<socket object" in repr(sock), "fd=" in repr(sock))
print(sock.fileno() >= 0)
print(sock.getblocking(), sock.gettimeout())
sock.close()
print(sock.fileno())
sock.close()

# The timeout model: blocking is "no timeout", non-blocking is "zero", anything else is a number.
sock = _socket.socket()
sock.setblocking(False)
print(sock.getblocking(), sock.gettimeout())
sock.setblocking(2)
print(sock.getblocking(), sock.gettimeout())
sock.settimeout(2.5)
print(sock.getblocking(), sock.gettimeout())
try:
    sock.settimeout(-1)
except ValueError as error:
    print(type(error).__name__, str(error))
try:
    sock.settimeout("x")
except TypeError as error:
    print(type(error).__name__, str(error))
sock.close()

# An AF_UNIX socketpair by default: two connected ends with no address to print.
left, right = _socket.socketpair()
print(left.family, left.type, left.proto)
print(repr(left.getsockname()), repr(left.getpeername()), repr(right.getsockname()))
left.send(b"hello")
print(right.recv(2), right.recv(100))
right.sendall(b"world")
print(left.recv(100))
left.close()
right.close()

# The same pair, refusing both ways: a non-blocking read and a read that runs out of time.
left, right = _socket.socketpair()
right.setblocking(False)
try:
    right.recv(1)
except BlockingIOError as error:
    print(type(error).__name__, error.errno, error.strerror)
right.settimeout(0.05)
print(right.getblocking(), right.gettimeout())
try:
    right.recv(1)
except TimeoutError as error:
    print(type(error).__name__, repr(error), error.errno)
left.close()
right.close()

# A real TCP round trip over the loopback, including a half close and the EOF it produces.
server = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
print(server.getsockname())
server.bind(("127.0.0.1", 0))
server.listen(1)
host, port = server.getsockname()
print(host, port > 0)
client = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
client.connect((host, port))
descriptor, peer = server._accept()
accepted = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM, 0, descriptor)
print(peer[0], peer[1] > 0)
print(client.getpeername()[0] == host, client.getpeername()[1] == port)
client.sendall(b"ping")
print(accepted.recv(4))
accepted.send(b"pong")
print(client.recv(4))
client.shutdown(_socket.SHUT_WR)
print(accepted.recv(1))
print(accepted.getpeername()[0])
accepted.close()
client.close()
server.close()

# A peer that was never connected is an error, not an address.
sock = _socket.socket()
try:
    sock.getpeername()
except OSError as error:
    print(error.errno)
sock.close()

# Options that come from the descriptor itself, and one round trip through setsockopt.
sock = _socket.socket()
print(sock.getsockopt(_socket.SOL_SOCKET, _socket.SO_TYPE))
print(sock.getsockopt(_socket.SOL_SOCKET, _socket.SO_ERROR))
sock.setsockopt(_socket.SOL_SOCKET, _socket.SO_REUSEADDR, 1)
print(sock.getsockopt(_socket.SOL_SOCKET, _socket.SO_REUSEADDR) != 0)
sock.close()

# Text to packed bytes and back, both families.
print(_socket.inet_aton("127.0.0.1"))
print(_socket.inet_ntoa(b"\x7f\x00\x00\x01"))
print(_socket.inet_aton("0.0.0.0"), _socket.inet_ntoa(_socket.inet_aton("10.1.2.3")))
print(_socket.inet_pton(_socket.AF_INET, "192.168.0.1"))
print(_socket.inet_ntop(_socket.AF_INET, b"\xc0\xa8\x00\x01"))
print(_socket.inet_pton(_socket.AF_INET6, "::1"))
print(
    _socket.inet_ntop(
        _socket.AF_INET6, _socket.inet_pton(_socket.AF_INET6, "2001:db8::1")
    )
)
for function, arguments in ((_socket.inet_aton, ("x",)), (_socket.inet_ntoa, (b"abc",))):
    try:
        function(*arguments)
    except OSError as error:
        print(error.errno, str(error))
try:
    _socket.inet_pton(9, "127.0.0.1")
except OSError as error:
    print(error.errno, error.strerror)
try:
    _socket.inet_pton(_socket.AF_INET, "999.1.1.1")
except OSError as error:
    print(error.errno, str(error))
try:
    _socket.inet_ntop(9, b"\x00")
except ValueError as error:
    print(str(error))
try:
    _socket.inet_ntop(_socket.AF_INET, b"abc")
except ValueError as error:
    print(str(error))

# The byte-order family, whose domain is the unsigned width of a C type.
print(_socket.htons(1), _socket.htons(256), _socket.ntohs(_socket.htons(4321)))
print(_socket.htonl(1), _socket.htonl(16909060), _socket.ntohl(_socket.htonl(123456789)))
for function, value, kind in (
    (_socket.htons, -1, ValueError),
    (_socket.htons, 65536, OverflowError),
    (_socket.htonl, -1, ValueError),
    (_socket.htonl, 1 << 32, OverflowError),
):
    try:
        function(value)
    except kind as error:
        print(type(error).__name__, str(error))

# Numeric address lookups only, so the answer does not depend on this machine's DNS.
print(_socket.getaddrinfo("127.0.0.1", 80, _socket.AF_INET, _socket.SOCK_STREAM))
print(_socket.getaddrinfo("::1", 80, _socket.AF_INET6, _socket.SOCK_STREAM))
try:
    _socket.getaddrinfo(
        "localhost", 80, _socket.AF_INET, _socket.SOCK_STREAM, 0, _socket.AI_NUMERICHOST
    )
except _socket.gaierror as error:
    print(type(error).__name__, error.errno, error.strerror)
print(_socket.gethostbyname("127.0.0.1"))
print(isinstance(_socket.gethostname(), str))
