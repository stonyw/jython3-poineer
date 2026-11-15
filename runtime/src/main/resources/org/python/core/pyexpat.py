"""pyexpat: the Expat-shaped parser the standard library expects.

The reference ``pyexpat`` is a binding to expat.  This runtime has no expat, so
the reader behind these classes is this runtime's own XML reader, fed through
the same callbacks and publishing the same surface.  The split is deliberate:
the callbacks, the attribute plumbing and the exception are Python here, and the
scanning, the entity table and the positions are the Java side.

What is not here is what expat alone can answer: the DTD declaration callbacks,
the external-entity machinery beyond a refusal, reparse deferral and the
amplification limits.  ``version_info`` says 2.5 so that a caller asking for the
2.6 behaviour is told the truth rather than handed a stub.
"""

XML_PARAM_ENTITY_PARSING_NEVER = 0
XML_PARAM_ENTITY_PARSING_UNLESS_STANDALONE = 1
XML_PARAM_ENTITY_PARSING_ALWAYS = 2

version_info = (2, 5, 0)
EXPAT_VERSION = 'expat_2.5.0'
native_encoding = 'UTF-8'

features = [
    ('XML_AT_ACT_THRES', 67108864),
    ('XML_AT_MAX_AMP', 100),
    ('XML_BLAP_ACT_THRES', 8388608),
    ('XML_BLAP_MAX_AMP', 100),
    ('XML_CONTEXT_BYTES', 1024),
    ('XML_DTD', 0),
    ('XML_GE', 0),
    ('XML_NS', 0),
    ('sizeof(XML_Char)', 1),
    ('sizeof(XML_LChar)', 1),
]

_HANDLERS = (
    'StartElementHandler',
    'EndElementHandler',
    'ProcessingInstructionHandler',
    'CharacterDataHandler',
    'UnparsedEntityDeclHandler',
    'EntityDeclHandler',
    'NotationDeclHandler',
    'StartNamespaceDeclHandler',
    'EndNamespaceDeclHandler',
    'CommentHandler',
    'StartCdataSectionHandler',
    'EndCdataSectionHandler',
    'DefaultHandler',
    'DefaultHandlerExpand',
    'NotStandaloneHandler',
    'ExternalEntityRefHandler',
    'StartDoctypeDeclHandler',
    'EndDoctypeDeclHandler',
    'ElementDeclHandler',
    'AttlistDeclHandler',
    'XmlDeclHandler',
    'SkippedEntityHandler',
)

_FLAGS = (
    'buffer_text',
    'ordered_attributes',
    'specified_attributes',
    'namespace_prefixes',
)


class xmlparser:
    """One parser.  expat calls the type ``pyexpat.xmlparser``."""

    StartElementHandler = None
    EndElementHandler = None
    ProcessingInstructionHandler = None
    CharacterDataHandler = None
    UnparsedEntityDeclHandler = None
    EntityDeclHandler = None
    NotationDeclHandler = None
    StartNamespaceDeclHandler = None
    EndNamespaceDeclHandler = None
    CommentHandler = None
    StartCdataSectionHandler = None
    EndCdataSectionHandler = None
    DefaultHandler = None
    DefaultHandlerExpand = None
    NotStandaloneHandler = None
    ExternalEntityRefHandler = None
    StartDoctypeDeclHandler = None
    EndDoctypeDeclHandler = None
    ElementDeclHandler = None
    AttlistDeclHandler = None
    XmlDeclHandler = None
    SkippedEntityHandler = None

    buffer_text = False
    ordered_attributes = False
    specified_attributes = False
    namespace_prefixes = False
    buffer_size = 8192

    def __setattr__(self, name, value):
        # expat's type has a fixed set of writable names: the callbacks and the
        # four switches.  Everything else is refused rather than filed away, and
        # a name that is not a string is the vectorcall complaint, not ours.
        if not isinstance(name, str):
            raise TypeError("attribute name must be string, not '%s'"
                            % type(name).__name__)
        if name in _FLAGS:
            object.__setattr__(self, name, bool(value))
        elif name == 'buffer_size':
            if isinstance(value, float):
                raise TypeError("'float' object cannot be interpreted as an integer")
            size = int(value)
            if size <= 0:
                raise ValueError("buffer_size must be greater than zero")
            if size > 0x7fffffff:
                raise OverflowError("Python int too large to convert to C int")
            object.__setattr__(self, name, size)
        elif name in _HANDLERS or name[:1] == '_':
            object.__setattr__(self, name, value)
        else:
            raise AttributeError("'pyexpat.xmlparser' object has no attribute %r"
                                 % (name,))

    # --- the read-only positions -------------------------------------------------------------

    @property
    def CurrentLineNumber(self):
        return _jy3_info(self, 'line')

    @property
    def CurrentColumnNumber(self):
        return _jy3_info(self, 'column')

    @property
    def CurrentByteIndex(self):
        return _jy3_info(self, 'byte')

    @property
    def ErrorLineNumber(self):
        return _jy3_info(self, 'error_line')

    @property
    def ErrorColumnNumber(self):
        return _jy3_info(self, 'error_column')

    @property
    def ErrorByteIndex(self):
        return _jy3_info(self, 'error_byte')

    @property
    def ErrorCode(self):
        return _jy3_info(self, 'error_code')

    # --- parsing ---------------------------------------------------------------------------

    def Parse(self, data, isfinal=False):
        return _jy3_parse(self, data, bool(isfinal))

    def ParseFile(self, file):
        return _jy3_parse_file(self, file)

    # --- the switches that exist and do nothing --------------------------------------------

    def GetReparseDeferralEnabled(self):
        return False

    def SetReparseDeferralEnabled(self, enabled):
        pass

    def GetInputContext(self):
        return None

    def GetBase(self):
        return _jy3_get_base(self)

    def SetBase(self, base):
        _jy3_set_base(self, base)

    def UseForeignDTD(self, flag=True):
        return None

    def SetParamEntityParsing(self, mode):
        return 1

    # --- the amplification switches ---------------------------------------------------------
    #
    # expat 2.4 added these.  The reader here does not count amplified bytes, so a
    # threshold set through them is remembered and never reached; the argument
    # checking is the part callers can observe, and it is the reference's.

    def SetBillionLaughsAttackProtectionActivationThreshold(self, threshold):
        return _jy3_set_activation_threshold(self, threshold)

    def SetBillionLaughsAttackProtectionMaximumAmplification(self, factor):
        return _jy3_set_max_amplification(self, factor)

    def ExternalEntityParserCreate(self, context, encoding=None):
        sub = xmlparser()
        sub._id = _jy3_new(encoding, self._separator, False)
        sub._separator = self._separator
        for name in _HANDLERS:
            value = getattr(self, name)
            if value is not None:
                setattr(sub, name, value)
        for name in _FLAGS:
            setattr(sub, name, getattr(self, name))
        sub.buffer_size = self.buffer_size
        return sub


XMLParserType = xmlparser


def ParserCreate(encoding=None, namespace_separator=None):
    if not isinstance(namespace_separator, str):
        if namespace_separator is not None:
            raise TypeError(
                "ParserCreate() argument 'namespace_separator' must be str or None, not %s"
                % type(namespace_separator).__name__)
    elif len(namespace_separator) > 1:
        raise ValueError(
            'namespace_separator must be at most one character, omitted, or None')
    if not isinstance(encoding, str):
        if encoding is not None:
            raise TypeError(
                "ParserCreate() argument 'encoding' must be str or None, not %s"
                % type(encoding).__name__)
    parser = xmlparser()
    parser._id = _jy3_new(encoding, namespace_separator, True)
    parser._separator = namespace_separator
    return parser
