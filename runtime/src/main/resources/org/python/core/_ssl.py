# _ssl: the TLS layer that ssl.py builds on.
#
# This runtime has no OpenSSL. What it has is the JDK's JSSE, so the types below wrap
# javax.net.ssl.SSLContext / SSLSocket rather than OpenSSL's SSL_CTX / SSL. Everything that
# touches the JVM is an injected `_jy3_*` function implemented in SslModule.java.
#
# Two consequences are stated here rather than hidden:
#
#   1. The constants below are the Python API's own values (the numbers a program compares
#      against), not a claim about an OpenSSL build. The version strings say exactly what this
#      is; nothing prints a fabricated OpenSSL version to satisfy a test.
#   2. `SSLContext` / `SSLSocket` are backed by a real TLS stack, but the OpenSSL-shaped knobs
#      JSSE does not have (PSK, NPN, TLS compression, tls-unique channel binding, session
#      ticket statistics) are absent or refused. `HAS_*` reports what actually exists.

# --- protocol versions ---------------------------------------------------------------------

PROTOCOL_SSLv23 = 2
PROTOCOL_TLS = 2
PROTOCOL_TLS_CLIENT = 16
PROTOCOL_TLS_SERVER = 17

PROTO_MINIMUM_SUPPORTED = -2
PROTO_MAXIMUM_SUPPORTED = -1
PROTO_SSLv3 = 768
PROTO_TLSv1 = 769
PROTO_TLSv1_1 = 770
PROTO_TLSv1_2 = 771
PROTO_TLSv1_3 = 772

# --- verify modes --------------------------------------------------------------------------

CERT_NONE = 0
CERT_OPTIONAL = 1
CERT_REQUIRED = 2

# --- verify flags --------------------------------------------------------------------------

VERIFY_DEFAULT = 0
VERIFY_CRL_CHECK_LEAF = 4
VERIFY_CRL_CHECK_CHAIN = 12
VERIFY_X509_STRICT = 32
VERIFY_ALLOW_PROXY_CERTS = 64
VERIFY_X509_TRUSTED_FIRST = 32768
VERIFY_X509_PARTIAL_CHAIN = 524288

# --- options; API values, remembered by the context ----------------------------------------

OP_ALL = 2147483712
OP_CIPHER_SERVER_PREFERENCE = 4194304
OP_ENABLE_KTLS = 8
OP_ENABLE_MIDDLEBOX_COMPAT = 1048576
OP_IGNORE_UNEXPECTED_EOF = 128
OP_LEGACY_SERVER_CONNECT = 4
OP_NO_COMPRESSION = 131072
OP_NO_RENEGOTIATION = 1073741824
OP_NO_SSLv2 = 0
OP_NO_SSLv3 = 33554432
OP_NO_TICKET = 16384
OP_NO_TLSv1 = 67108864
OP_NO_TLSv1_1 = 268435456
OP_NO_TLSv1_2 = 134217728
OP_NO_TLSv1_3 = 536870912
OP_SINGLE_DH_USE = 0
OP_SINGLE_ECDH_USE = 0

# --- host name checking flags ---------------------------------------------------------------

HOSTFLAG_ALWAYS_CHECK_SUBJECT = 1
HOSTFLAG_NO_WILDCARDS = 2
HOSTFLAG_NO_PARTIAL_WILDCARDS = 4
HOSTFLAG_MULTI_LABEL_WILDCARDS = 8
HOSTFLAG_SINGLE_LABEL_SUBDOMAINS = 16
HOSTFLAG_NEVER_CHECK_SUBJECT = 32

# --- error codes ---------------------------------------------------------------------------

SSL_ERROR_SSL = 1
SSL_ERROR_WANT_READ = 2
SSL_ERROR_WANT_WRITE = 3
SSL_ERROR_WANT_X509_LOOKUP = 4
SSL_ERROR_SYSCALL = 5
SSL_ERROR_ZERO_RETURN = 6
SSL_ERROR_WANT_CONNECT = 7
SSL_ERROR_EOF = 8
SSL_ERROR_INVALID_ERROR_CODE = 10

# --- alerts --------------------------------------------------------------------------------

ALERT_DESCRIPTION_CLOSE_NOTIFY = 0
ALERT_DESCRIPTION_UNEXPECTED_MESSAGE = 10
ALERT_DESCRIPTION_BAD_RECORD_MAC = 20
ALERT_DESCRIPTION_DECOMPRESSION_FAILURE = 30
ALERT_DESCRIPTION_HANDSHAKE_FAILURE = 40
ALERT_DESCRIPTION_NO_CERTIFICATE = 41
ALERT_DESCRIPTION_BAD_CERTIFICATE = 42
ALERT_DESCRIPTION_UNSUPPORTED_CERTIFICATE = 43
ALERT_DESCRIPTION_CERTIFICATE_REVOKED = 44
ALERT_DESCRIPTION_CERTIFICATE_EXPIRED = 45
ALERT_DESCRIPTION_CERTIFICATE_UNKNOWN = 46
ALERT_DESCRIPTION_ILLEGAL_PARAMETER = 47
ALERT_DESCRIPTION_UNKNOWN_CA = 48
ALERT_DESCRIPTION_ACCESS_DENIED = 49
ALERT_DESCRIPTION_DECODE_ERROR = 50
ALERT_DESCRIPTION_DECRYPT_ERROR = 51
ALERT_DESCRIPTION_PROTOCOL_VERSION = 70
ALERT_DESCRIPTION_INSUFFICIENT_SECURITY = 71
ALERT_DESCRIPTION_INTERNAL_ERROR = 80
ALERT_DESCRIPTION_USER_CANCELLED = 90
ALERT_DESCRIPTION_NO_RENEGOTIATION = 100
ALERT_DESCRIPTION_UNSUPPORTED_EXTENSION = 110
ALERT_DESCRIPTION_CERTIFICATE_UNOBTAINABLE = 111
ALERT_DESCRIPTION_UNRECOGNIZED_NAME = 112
ALERT_DESCRIPTION_BAD_CERTIFICATE_STATUS_RESPONSE = 113
ALERT_DESCRIPTION_BAD_CERTIFICATE_HASH_VALUE = 114
ALERT_DESCRIPTION_UNKNOWN_PSK_IDENTITY = 115

# --- what this stack actually offers -------------------------------------------------------

HAS_ALPN = True
HAS_ECDH = True
HAS_NPN = False
HAS_PHA = False
HAS_PSK = False
HAS_SNI = True
HAS_SSLv2 = False
HAS_SSLv3 = False
HAS_TLS_UNIQUE = False
HAS_TLSv1 = False
HAS_TLSv1_1 = False
HAS_TLSv1_2 = True
HAS_TLSv1_3 = True

# --- version, stated honestly --------------------------------------------------------------

# These are deliberately not OpenSSL numbers. The JDK's TLS stack is not OpenSSL, and a
# program that branches on OPENSSL_VERSION_NUMBER should be told so rather than be handed a
# number copied from a release it is not running.
OPENSSL_VERSION = "Jy3 JSSE (not OpenSSL)"
OPENSSL_VERSION_NUMBER = 0
OPENSSL_VERSION_INFO = (0, 0, 0, 0, 0)
_OPENSSL_API_VERSION = (0, 0, 0, 0, 0)

# JSSE has no OpenSSL cipher string grammar. "DEFAULT" selects the platform defaults.
_DEFAULT_CIPHERS = "DEFAULT"

ENCODING_PEM = 1
ENCODING_DER = 2


# --- exceptions ----------------------------------------------------------------------------
#
# The reference creates these with the module name `ssl`, and ssl.py re-exports them under that
# name, so the classes are stamped accordingly here.

class SSLError(OSError):
    # CPython's `_ssl` displays the message alone rather than OSError's "[Errno N] message",
    # because the errno of an SSL failure is an OpenSSL error code and not an operating system
    # one. The library/reason pair is not set here: the raising code fills it in, and a value
    # made by hand has neither name, exactly as in the reference.
    def __str__(self):
        if self.errno is not None and self.strerror is not None:
            return self.strerror
        return repr(self.args)


class SSLZeroReturnError(SSLError):
    pass


class SSLWantReadError(SSLError):
    pass


class SSLWantWriteError(SSLError):
    pass


class SSLSyscallError(SSLError):
    pass


class SSLEOFError(SSLError):
    pass


class SSLCertVerificationError(SSLError, ValueError):
    pass


for _cls in (SSLError, SSLZeroReturnError, SSLWantReadError, SSLWantWriteError,
             SSLSyscallError, SSLEOFError, SSLCertVerificationError):
    _cls.__module__ = "ssl"
del _cls


# --- ASN.1 object names --------------------------------------------------------------------
#
# (nid, short name, long name, OID). The table covers the names ssl.py needs at import time
# (the two Extended Key Usage purposes) plus the distinguished-name attributes and key usages
# a certificate dict is built from. It is a table, not a claim to be OpenSSL's full OID store.

_OBJECTS = (
    (13, "CN", "commonName", "2.5.4.3"),
    (14, "SN", "surname", "2.5.4.4"),
    (15, "C", "countryName", "2.5.4.6"),
    (16, "L", "localityName", "2.5.4.7"),
    (17, "ST", "stateOrProvinceName", "2.5.4.8"),
    (18, "O", "organizationName", "2.5.4.10"),
    (19, "OU", "organizationalUnitName", "2.5.4.11"),
    (20, "title", "title", "2.5.4.12"),
    (21, "G", "givenName", "2.5.4.42"),
    (22, "I", "initials", "2.5.4.43"),
    (23, "GN", "generationQualifier", "2.5.4.44"),
    (24, "dnQualifier", "dnQualifier", "2.5.4.46"),
    (25, "pseudonym", "pseudonym", "2.5.4.65"),
    (48, "emailAddress", "emailAddress", "1.2.840.113549.1.9.1"),
    (30, "DC", "domainComponent", "0.9.2342.19200300.100.1.25"),
    (129, "serverAuth", "TLS Web Server Authentication", "1.3.6.1.5.5.7.3.1"),
    (130, "clientAuth", "TLS Web Client Authentication", "1.3.6.1.5.5.7.3.2"),
    (131, "codeSigning", "Code Signing", "1.3.6.1.5.5.7.3.3"),
    (132, "emailProtection", "E-mail Protection", "1.3.6.1.5.5.7.3.4"),
    (133, "timeStamping", "Time Stamping", "1.3.6.1.5.5.7.3.8"),
    (134, "OCSPSigning", "OCSP Signing", "1.3.6.1.5.5.7.3.9"),
    (135, "ipsecIKE", "ipsec Internet Key Exchange", "1.3.6.1.5.5.7.3.17"),
    (136, "msCodeInd", "Microsoft Individual Code Signing", "1.3.6.1.4.1.311.2.1.21"),
    (137, "msCodeCom", "Microsoft Commercial Code Signing", "1.3.6.1.4.1.311.2.1.22"),
    (138, "msCTLSign", "Microsoft Trust List Signing", "1.3.6.1.4.1.311.10.3.1"),
    (139, "msEFS", "Microsoft Encrypted File System", "1.3.6.1.4.1.311.10.3.4"),
)

_BY_OID = {}
_BY_NAME = {}
_BY_NID = {}
for _nid, _short, _long, _oid in _OBJECTS:
    _entry = (_nid, _short, _long, _oid)
    _BY_OID[_oid] = _entry
    _BY_NID[_nid] = _entry
    # Names are matched exactly: 'serverAuth' is an object, 'serverauth' is not.
    _BY_NAME[_short] = _entry
    _BY_NAME[_long] = _entry
del _nid, _short, _long, _oid, _entry


def txt2obj(txt, name=False):
    """Look up (nid, short name, long name, OID) by OID, and by name when name is true."""
    if not isinstance(txt, str):
        raise TypeError("txt2obj() argument 'txt' must be str, not %s"
                        % type(txt).__name__)
    entry = _BY_OID.get(txt)
    if entry is None and name:
        entry = _BY_NAME.get(txt)
    if entry is None:
        raise ValueError("unknown object '%s'" % txt)
    return entry


def nid2obj(nid):
    """Look up (nid, short name, long name, OID) by numeric NID."""
    entry = _BY_NID.get(int(nid))
    if entry is None:
        raise ValueError("unknown NID %d" % int(nid))
    return entry


# --- randomness ----------------------------------------------------------------------------

def RAND_status():
    return True


def RAND_add(data, entropy):
    return None


def RAND_bytes(n):
    return _jy3_rand_bytes(n)


def get_default_verify_paths():
    return _jy3_default_verify_paths()


# --- memory BIO ----------------------------------------------------------------------------
#
# A real buffer, matching the reference's byte-for-byte: write appends, read drains from the
# front, write_eof forbids further writes and sets eof.

class MemoryBIO:
    __slots__ = ("_data", "_eof")

    def __init__(self):
        self._data = bytearray()
        self._eof = False

    @property
    def pending(self):
        return len(self._data)

    @property
    def eof(self):
        # EOF is only reached once every byte fed before it has been read.
        return self._eof and not self._data

    def read(self, n=-1):
        if n is None:
            n = -1
        n = int(n)
        size = len(self._data)
        if n < 0 or n > size:
            n = size
        out = bytes(self._data[:n])
        del self._data[:n]
        return out

    def write(self, data):
        if self._eof:
            raise SSLError("cannot write() after write_eof()")
        octets = _jy3_octets(data)
        self._data.extend(octets)
        return len(octets)

    def write_eof(self):
        self._eof = True


# --- certificates --------------------------------------------------------------------------

class Certificate:
    __slots__ = ("_der",)

    def __new__(cls, *args, **kwargs):
        raise TypeError("cannot create '_ssl.Certificate' instances")

    @classmethod
    def _from_der(cls, der):
        self = object.__new__(cls)
        self._der = bytes(der)
        return self

    def public_bytes(self, encoding):
        if encoding == ENCODING_DER:
            return self._der
        if encoding == ENCODING_PEM:
            return _jy3_pem_encode(self._der)
        raise ValueError("Unsupported encoding")

    def get_info(self):
        return _jy3_cert_info(self._der)


class SSLSession:
    __slots__ = ("id", "time", "timeout", "ticket_lifetime_hint", "has_ticket")

    def __new__(cls, *args, **kwargs):
        raise TypeError("cannot create '_ssl.SSLSession' instances")

    @classmethod
    def _from_parts(cls, parts):
        self = object.__new__(cls)
        (self.id, self.time, self.timeout,
         self.ticket_lifetime_hint, self.has_ticket) = parts
        return self


# --- context -------------------------------------------------------------------------------

class _SSLContext:
    def __new__(cls, protocol):
        self = object.__new__(cls)
        self._id = _jy3_ctx_new(protocol)
        return self

    def _get(self, field):
        return _jy3_ctx_get(self._id, field)

    def _set(self, field, value):
        _jy3_ctx_set(self._id, field, value)

    @property
    def protocol(self):
        return self._get("protocol")

    @property
    def verify_mode(self):
        return self._get("verify_mode")

    @verify_mode.setter
    def verify_mode(self, value):
        value = int(value)
        if value not in (CERT_NONE, CERT_OPTIONAL, CERT_REQUIRED):
            raise ValueError("verify_mode must be CERT_NONE, CERT_OPTIONAL or CERT_REQUIRED")
        if value == CERT_NONE and self._get("check_hostname"):
            raise ValueError(
                "Cannot set verify_mode to CERT_NONE when check_hostname is enabled.")
        self._set("verify_mode", value)

    @property
    def check_hostname(self):
        return self._get("check_hostname")

    @check_hostname.setter
    def check_hostname(self, value):
        value = bool(value)
        # Enabling host name checking implies certificate verification; the reference turns
        # CERT_NONE into CERT_REQUIRED here rather than refusing the assignment. The reverse
        # direction (verify_mode = CERT_NONE while checking host names) is the error.
        if value and self._get("verify_mode") == CERT_NONE:
            self._set("verify_mode", CERT_REQUIRED)
        self._set("check_hostname", value)

    @property
    def minimum_version(self):
        return self._get("minimum_version")

    @minimum_version.setter
    def minimum_version(self, value):
        value = int(value)
        if value == PROTO_SSLv3:
            raise ValueError("Unsupported protocol version 0x300")
        if value == PROTO_MAXIMUM_SUPPORTED:
            value = PROTO_TLSv1_3
        if value < PROTO_MINIMUM_SUPPORTED or value > PROTO_TLSv1_3:
            raise ValueError("Unsupported protocol version 0x%x" % value)
        self._set("minimum_version", value)

    @property
    def maximum_version(self):
        return self._get("maximum_version")

    @maximum_version.setter
    def maximum_version(self, value):
        value = int(value)
        if value == PROTO_SSLv3:
            raise ValueError("Unsupported protocol version 0x300")
        if value == PROTO_MINIMUM_SUPPORTED:
            # The reference resolves the "no version" sentinel to the newest version it has.
            value = PROTO_TLSv1_3
        if value < PROTO_MINIMUM_SUPPORTED or value > PROTO_TLSv1_3:
            raise ValueError("Unsupported protocol version 0x%x" % value)
        self._set("maximum_version", value)

    @property
    def options(self):
        return self._get("options")

    @options.setter
    def options(self, value):
        # The reference's setter is a C unsigned long member: a non-integer is a TypeError,
        # a negative value a ValueError and an oversized one an OverflowError. Converting
        # with int() here would turn "abc" into a ValueError instead.
        self._set("options", value)

    @property
    def verify_flags(self):
        return self._get("verify_flags")

    @verify_flags.setter
    def verify_flags(self, value):
        self._set("verify_flags", int(value))

    @property
    def _host_flags(self):
        return self._get("host_flags")

    @_host_flags.setter
    def _host_flags(self, value):
        self._set("host_flags", int(value))

    @property
    def post_handshake_auth(self):
        return self._get("post_handshake_auth")

    @post_handshake_auth.setter
    def post_handshake_auth(self, value):
        self._set("post_handshake_auth", bool(value))

    @property
    def num_tickets(self):
        return self._get("num_tickets")

    @num_tickets.setter
    def num_tickets(self, value):
        self._set("num_tickets", int(value))

    @property
    def security_level(self):
        return self._get("security_level")

    @security_level.setter
    def security_level(self, value):
        self._set("security_level", int(value))

    @property
    def keylog_filename(self):
        return self._get("keylog_filename")

    @keylog_filename.setter
    def keylog_filename(self, value):
        self._set("keylog_filename", value)

    @property
    def sni_callback(self):
        return self._get("sni_callback")

    @sni_callback.setter
    def sni_callback(self, value):
        self._set("sni_callback", value)

    @property
    def _msg_callback(self):
        return self._get("msg_callback")

    @_msg_callback.setter
    def _msg_callback(self, value):
        self._set("msg_callback", value)

    def load_cert_chain(self, certfile, keyfile=None, password=None):
        _jy3_ctx_load_cert_chain(self._id, certfile, keyfile, password)

    def load_verify_locations(self, cafile=None, capath=None, cadata=None):
        _jy3_ctx_load_verify_locations(self._id, cafile, capath, cadata)

    def set_default_verify_paths(self):
        _jy3_ctx_set_default_verify_paths(self._id)

    def set_ciphers(self, ciphers):
        _jy3_ctx_set_ciphers(self._id, ciphers)

    def set_ecdh_curve(self, name):
        _jy3_ctx_set_ecdh_curve(self._id, name)

    def get_ca_certs(self, binary_form=False):
        return _jy3_ctx_get_ca_certs(self._id, binary_form)

    def get_ciphers(self):
        return _jy3_ctx_get_ciphers(self._id)

    def cert_store_stats(self):
        return _jy3_ctx_cert_store_stats(self._id)

    def session_stats(self):
        return _jy3_ctx_session_stats(self._id)

    def load_dh_params(self, path):
        _jy3_ctx_load_dh_params(self._id, path)

    def _set_alpn_protocols(self, protocols):
        _jy3_ctx_set_alpn_protocols(self._id, protocols)

    def _set_npn_protocols(self, protocols):
        raise NotImplementedError("NPN is not supported by the JVM TLS stack")

    def set_psk_client_callback(self, callback):
        raise NotImplementedError("PSK is not supported by the JVM TLS stack")

    def set_psk_server_callback(self, callback, identity_hint=None):
        raise NotImplementedError("PSK is not supported by the JVM TLS stack")

    def _wrap_socket(self, sock, server_side, server_hostname, owner=None, session=None):
        sslid = _jy3_ctx_wrap_socket(self._id, sock, server_side, server_hostname)
        obj = object.__new__(_SSLSocket)
        obj._id = sslid
        obj._context = self
        obj._owner = owner if owner is not None else sock
        obj.server_side = bool(server_side)
        obj.server_hostname = server_hostname
        obj._closed = False
        obj._pending_session = session
        return obj

    def _wrap_bio(self, incoming, outgoing, server_side=False, server_hostname=None,
                  owner=None, session=None):
        raise NotImplementedError(
            "_wrap_bio is not implemented: the SSLEngine memory-BIO path is not wired yet")


# --- socket wrapper ------------------------------------------------------------------------

class _SSLSocket:
    __slots__ = ("_id", "_context", "_owner", "server_side", "server_hostname",
                 "_closed", "_pending_session")

    def __new__(cls, *args, **kwargs):
        raise TypeError("cannot create '_ssl._SSLSocket' instances")

    @property
    def context(self):
        return self._context

    @context.setter
    def context(self, value):
        self._context = value
        _jy3_ssl_set_context(self._id, value._id)

    @property
    def owner(self):
        return self._owner

    @property
    def session(self):
        parts = _jy3_ssl_session(self._id)
        if parts is not None:
            return SSLSession._from_parts(parts)
        return self._pending_session

    @session.setter
    def session(self, value):
        self._pending_session = value
        _jy3_ssl_set_session(self._id, value)

    @property
    def session_reused(self):
        return _jy3_ssl_session_reused(self._id)

    def _sync_timeout(self):
        # `SSLSocket.settimeout()` writes the timeout on the Python socket, not on this object;
        # handing it over before each operation keeps read/write/handshake on the value the
        # program set most recently.
        owner = self._owner
        if owner is not None:
            _jy3_ssl_set_timeout(self._id, owner.gettimeout())

    def read(self, length=1024, buffer=None):
        self._sync_timeout()
        if buffer is not None:
            return _jy3_ssl_read_into(self._id, buffer, length)
        return _jy3_ssl_read(self._id, length)

    def write(self, data):
        self._sync_timeout()
        return _jy3_ssl_write(self._id, data)

    def do_handshake(self):
        self._sync_timeout()
        _jy3_ssl_do_handshake(self._id)

    def getpeercert(self, binary_form=False):
        return _jy3_ssl_getpeercert(self._id, bool(binary_form))

    def get_verified_chain(self):
        return [Certificate._from_der(der)
                for der in _jy3_ssl_get_chain(self._id, True)]

    def get_unverified_chain(self):
        return [Certificate._from_der(der)
                for der in _jy3_ssl_get_chain(self._id, False)]

    def selected_alpn_protocol(self):
        return _jy3_ssl_alpn(self._id)

    def cipher(self):
        return _jy3_ssl_cipher(self._id)

    def shared_ciphers(self):
        return _jy3_ssl_shared_ciphers(self._id)

    def compression(self):
        return None

    def pending(self):
        return _jy3_ssl_pending(self._id)

    def shutdown(self):
        owner = self._owner
        _jy3_ssl_shutdown(self._id)
        self._closed = True
        return owner

    def version(self):
        return _jy3_ssl_version(self._id)

    def get_channel_binding(self, cb_type="tls-unique"):
        return _jy3_ssl_channel_binding(self._id, cb_type)

    def verify_client_post_handshake(self):
        raise NotImplementedError(
            "post-handshake authentication is not supported by the JVM TLS stack")


# --- module helpers ------------------------------------------------------------------------

def _test_decode_cert(path):
    """Parse the first certificate in a PEM file and return its dict, as the tests use it."""
    return _jy3_decode_cert(path)
