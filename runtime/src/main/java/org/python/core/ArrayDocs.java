package org.python.core;

/**
 * The reference interpreter's own prose for {@code array}, transcribed from its
 * {@code array.array.__doc__}, its {@code array} module doc, and the method
 * documents, so that a program reading any of them reads the same bytes it would there.
 *
 * <p>Generated from the reference 3.14.10 build once; nothing here is written by hand.
 */
final class ArrayDocs {

    private ArrayDocs() {
    }

    static final String TYPE_DOC =
            "array(typecode [, initializer]) -> array\n\nReturn a new array whose items are restricted by typecode, and\ninitialized from the optional initializer value, which must be a list,\nstring or iterable over elements of the appropriate type.\n\nArrays represent basic values and behave very much like lists, except\nthe type of objects stored in them is constrained. The type is specified\nat object creation time by using a type code, which is a single character.\nThe following type codes are defined:\n\n    Type code   C Type             Minimum size in bytes\n    'b'         signed integer     1\n    'B'         unsigned integer   1\n    'u'         Unicode character  2 (see note)\n    'w'         Py_UCS4            4\n    'h'         signed integer     2\n    'H'         unsigned integer   2\n    'i'         signed integer     2\n    'I'         unsigned integer   2\n    'l'         signed integer     4\n    'L'         unsigned integer   4\n    'q'         signed integer     8 (see note)\n    'Q'         unsigned integer   8 (see note)\n    'f'         floating-point     4\n    'd'         floating-point     8\n\nNOTE: The 'u' typecode corresponds to Python's unicode character. On\nnarrow builds this is 2-bytes on wide builds this is 4-bytes.\n\nNOTE: The 'q' and 'Q' type codes are only available if the platform\nC compiler used to build Python supports 'long long', or, on Windows,\n'__int64'.\n\nMethods:\n\nappend() -- append a new item to the end of the array\nbuffer_info() -- return information giving the current memory info\nbyteswap() -- byteswap all the items of the array\ncount() -- return number of occurrences of an object\nextend() -- extend array by appending multiple elements from an iterable\nfromfile() -- read items from a file object\nfromlist() -- append items from the list\nfrombytes() -- append items from the string\nindex() -- return index of first occurrence of an object\ninsert() -- insert a new item into the array at a provided position\npop() -- remove and return item (default last)\nremove() -- remove first occurrence of an object\nreverse() -- reverse the order of the items in the array\ntofile() -- write all items to a file object\ntolist() -- return the array converted to an ordinary list\ntobytes() -- return the array converted to a string\n\nAttributes:\n\ntypecode -- the typecode character used to create the array\nitemsize -- the length in bytes of one array item\n";

    static final String MODULE_DOC =
            "This module defines an object type which can efficiently represent\nan array of basic values: characters, integers, floating-point\nnumbers.  Arrays are sequence types and behave very much like lists,\nexcept that the type of objects stored in them is constrained.\n";

    static final String APPEND_DOC =
            "Append new value v to the end of the array.";

    static final String BUFFER_INFO_DOC =
            "Return a tuple (address, length) giving the current memory address and the length in items of the buffer used to hold array's contents.\n\nThe length should be multiplied by the itemsize attribute to\ncalculate the buffer length in bytes.";

    static final String BYTESWAP_DOC =
            "Byteswap all items of the array.\n\nIf the items in the array are not 1, 2, 4, or 8 bytes in size,\nRuntimeError is raised.";

    static final String CLEAR_DOC =
            "Remove all items from the array.";

    static final String COUNT_DOC =
            "Return number of occurrences of v in the array.";

    static final String EXTEND_DOC =
            "Append items to the end of the array.";

    static final String FROMBYTES_DOC =
            "Appends items from the string, interpreting it as an array of machine values, as if it had been read from a file using the fromfile() method.";

    static final String FROMFILE_DOC =
            "Read n objects from the file object f and append them to the end of the array.";

    static final String FROMLIST_DOC =
            "Append items to array from list.";

    static final String FROMUNICODE_DOC =
            "Extends this array with data from the unicode string ustr.\n\nThe array must be a unicode type array; otherwise a ValueError is\nraised.  Use array.frombytes(ustr.encode(...)) to append Unicode\ndata to an array of some other type.";

    static final String INDEX_DOC =
            "Return index of first occurrence of v in the array.\n\nRaise ValueError if the value is not present.";

    static final String INSERT_DOC =
            "Insert a new item v into the array before position i.";

    static final String POP_DOC =
            "Return the i-th element and delete it from the array.\n\ni defaults to -1.";

    static final String REMOVE_DOC =
            "Remove the first occurrence of v in the array.";

    static final String REVERSE_DOC =
            "Reverse the order of the items in the array.";

    static final String TOBYTES_DOC =
            "Convert the array to an array of machine values and return the bytes representation.";

    static final String TOFILE_DOC =
            "Write all items (as machine values) to the file object f.";

    static final String TOLIST_DOC =
            "Convert array to an ordinary list with the same items.";

    static final String TOUNICODE_DOC =
            "Extends this array with data from the unicode string ustr.\n\nConvert the array to a unicode string.  The array must be a unicode\ntype array; otherwise a ValueError is raised.  Use\narray.tobytes().decode() to obtain a unicode string from an array of\nsome other type.";

    static final String SIZEOF_DOC =
            "Size of the array in memory, in bytes.";

    static final String COPY_DOC =
            "Return a copy of the array.";

    static final String DEEPCOPY_DOC =
            "Return a copy of the array.";

}