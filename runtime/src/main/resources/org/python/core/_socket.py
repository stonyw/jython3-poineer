# _socket: the native socket layer that socket.py builds on.
#
# The types and the module functions are written here in Python because socket.py subclasses
# `_socket.socket` and declares its own `__slots__`; a base class with a `__dict__` would refuse
# that. Everything that has to touch a file descriptor is an injected `_jy3_*` function, which the
# Java side implements on java.nio.channels. This module deliberately has no `__all__`: the
# reference has none either, and socket.py copies its public names with `os._get_exports_list`,
# which filters the ones starting with an underscore.
#
# errno values and messages are the macOS ones, which is what this runtime is measured against.

AF_UNSPEC = 0
AF_UNIX = 1
AF_INET = 2
AF_INET6 = 30

SOCK_STREAM = 1
SOCK_DGRAM = 2
SOCK_RAW = 3
SOCK_SEQPACKET = 5

SOL_SOCKET = 65535

SO_DEBUG = 1
SO_ACCEPTCONN = 2
SO_REUSEADDR = 4
SO_KEEPALIVE = 8
SO_DONTROUTE = 16
SO_BROADCAST = 32
SO_USELOOPBACK = 64
SO_LINGER = 128
SO_OOBINLINE = 256
SO_REUSEPORT = 512
SO_SNDBUF = 4097
SO_RCVBUF = 4098
SO_SNDLOWAT = 4099
SO_RCVLOWAT = 4100
SO_SNDTIMEO = 4101
SO_RCVTIMEO = 4102
SO_ERROR = 4103
SO_TYPE = 4104

SHUT_RD = 0
SHUT_WR = 1
SHUT_RDWR = 2

MSG_OOB = 1
MSG_PEEK = 2
MSG_DONTROUTE = 4
MSG_EOR = 8
MSG_TRUNC = 16
MSG_CTRUNC = 32
MSG_WAITALL = 64
MSG_DONTWAIT = 128
MSG_EOF = 256

IPPROTO_IP = 0
IPPROTO_TCP = 6
IPPROTO_UDP = 17
IPPROTO_IPV6 = 41

IPV6_V6ONLY = 27

TCP_NODELAY = 1
TCP_MAXSEG = 2

AI_PASSIVE = 1
AI_CANONNAME = 2
AI_NUMERICHOST = 4
AI_ALL = 256
AI_ADDRCONFIG = 1024
AI_V4MAPPED = 2048
AI_NUMERICSERV = 4096
AI_DEFAULT = 3072

INADDR_ANY = 0
INADDR_BROADCAST = 0xFFFFFFFF
INADDR_LOOPBACK = 0x7F000001
INADDR_NONE = 0xFFFFFFFF

IPPORT_RESERVED = 1024
SOMAXCONN = 128

has_ipv6 = True


class socket:
    """A socket, described by a file descriptor this runtime hands out."""

    __slots__ = ("_fd", "_family", "_type", "_proto", "_timeout")

    def __init__(self, family=-1, type=-1, proto=-1, fileno=None):
        if fileno is None:
            if family == -1:
                family = AF_INET
            if type == -1:
                type = SOCK_STREAM
            if proto == -1:
                proto = 0
            fd = _jy3_new(family, type, proto)
        else:
            fd = _jy3_index(fileno)
            family, type, proto = _jy3_info(fd, family, type, proto)
        self._fd = fd
        self._family = family
        self._type = type
        self._proto = proto
        self._timeout = getdefaulttimeout()

    @property
    def family(self):
        return self._family

    @property
    def type(self):
        return self._type

    @property
    def proto(self):
        return self._proto

    def __repr__(self):
        return "<socket object, fd=%d, family=%d, type=%d, proto=%d>" % (
            self.fileno(), self.family, self.type, self.proto)

    def fileno(self):
        return self._fd

    def close(self):
        fd = self._fd
        if fd >= 0:
            self._fd = -1
            _jy3_close(fd)

    def detach(self):
        fd = self._fd
        if fd < 0:
            raise OSError(9, "Bad file descriptor")
        self._fd = -1
        return fd

    def bind(self, address):
        _jy3_bind(self._fd, address)

    def listen(self, backlog=None):
        if backlog is None:
            backlog = SOMAXCONN
        _jy3_listen(self._fd, backlog)

    def _accept(self):
        return _jy3_accept(self._fd, self._timeout)

    def connect(self, address):
        _jy3_connect(self._fd, address, self._timeout)

    def connect_ex(self, address):
        return _jy3_connect_ex(self._fd, address, self._timeout)

    def send(self, data, flags=0):
        return _jy3_send(self._fd, data, flags, self._timeout)

    def sendall(self, data, flags=0):
        _jy3_sendall(self._fd, data, flags, self._timeout)

    def sendto(self, data, *args):
        if len(args) == 1:
            flags = 0
            address = args[0]
        elif len(args) == 2:
            flags, address = args
        else:
            raise TypeError(
                "sendto() takes 2 or 3 arguments (%d given)" % (len(args) + 1))
        return _jy3_sendto(self._fd, data, flags, address, self._timeout)

    def recv(self, bufsize, flags=0):
        return _jy3_recv(self._fd, bufsize, flags, self._timeout)

    def recv_into(self, buffer, nbytes=0, flags=0):
        return _jy3_recv_into(self._fd, buffer, nbytes, flags, self._timeout)

    def recvfrom(self, bufsize, flags=0):
        return _jy3_recvfrom(self._fd, bufsize, flags, self._timeout)

    def recvfrom_into(self, buffer, nbytes=0, flags=0):
        return _jy3_recvfrom_into(self._fd, buffer, nbytes, flags, self._timeout)

    def shutdown(self, how):
        _jy3_shutdown(self._fd, how)

    def getsockname(self):
        return _jy3_getsockname(self._fd)

    def getpeername(self):
        return _jy3_getpeername(self._fd)

    def getsockopt(self, level, optname, buflen=None):
        return _jy3_getsockopt(self._fd, level, optname, buflen)

    def setsockopt(self, level, optname, value, optlen=None):
        _jy3_setsockopt(self._fd, level, optname, value)

    def gettimeout(self):
        return self._timeout

    def settimeout(self, value):
        if value is None:
            self._timeout = None
            return
        value = _jy3_as_double(value)
        if value < 0:
            raise ValueError("Timeout value out of range")
        self._timeout = value

    def setblocking(self, flag):
        self._timeout = 0.0 if not flag else None

    def getblocking(self):
        return self._timeout != 0.0


SocketType = socket


def socketpair(family=None, type=SOCK_STREAM, proto=0):
    if family is None:
        family = AF_UNIX
    a, b = _jy3_pair(family, type, proto)
    return (socket(family, type, proto, a), socket(family, type, proto, b))


def close(fd):
    _jy3_close(fd)


def dup(fd):
    return _jy3_dup(fd)


def gethostname():
    return _jy3_gethostname()


def gethostbyname(hostname):
    return _jy3_gethostbyname(hostname)


def getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
    return _jy3_getaddrinfo(host, port, family, type, proto, flags)


def inet_aton(ip_address):
    return _jy3_inet_aton(ip_address)


def inet_ntoa(packed_ip):
    return _jy3_inet_ntoa(packed_ip)


def inet_pton(address_family, ip_string):
    return _jy3_inet_pton(address_family, ip_string)


def inet_ntop(address_family, packed_ip):
    return _jy3_inet_ntop(address_family, packed_ip)


def htons(x):
    return _jy3_htons(x)


def htonl(x):
    return _jy3_htonl(x)


def ntohs(x):
    return _jy3_ntohs(x)


def ntohl(x):
    return _jy3_ntohl(x)


_default_timeout = None


def getdefaulttimeout():
    return _default_timeout


def setdefaulttimeout(timeout):
    global _default_timeout
    if timeout is None:
        _default_timeout = None
        return
    timeout = _jy3_as_double(timeout)
    if timeout < 0:
        raise ValueError("Timeout value out of range")
    _default_timeout = timeout
