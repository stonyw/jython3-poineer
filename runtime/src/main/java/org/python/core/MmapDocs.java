package org.python.core;

/**
 * The reference interpreter's own prose for {@code mmap.mmap}, transcribed from its
 * {@code mmap.mmap.__doc__}, so that a program reading the type's doc reads the same bytes it would
 * there. The module itself has no doc, and none of the methods have one.
 *
 * <p>Generated from the reference 3.14.10 build once; nothing here is written by hand.
 */
final class MmapDocs {

    private MmapDocs() {
    }

    static final String TYPE_DOC =
            "Windows: mmap(fileno, length[, tagname[, access[, offset]]])\n"
                    + "\n"
                    + "Maps length bytes from the file specified by the file handle fileno,\n"
                    + "and returns a mmap object.  If length is larger than the current size\n"
                    + "of the file, the file is extended to contain length bytes.  If length\n"
                    + "is 0, the maximum length of the map is the current size of the file,\n"
                    + "except that if the file is empty Windows raises an exception (you cannot\n"
                    + "create an empty mapping on Windows).\n"
                    + "\n"
                    + "Unix: mmap(fileno, length[, flags[, prot[, access[, offset]]]])\n"
                    + "\n"
                    + "Maps length bytes from the file specified by the file descriptor fileno,\n"
                    + "and returns a mmap object.  If length is 0, the maximum length of the map\n"
                    + "will be the current size of the file when mmap is called.\n"
                    + "flags specifies the nature of the mapping. MAP_PRIVATE creates a\n"
                    + "private copy-on-write mapping, so changes to the contents of the mmap\n"
                    + "object will be private to this process, and MAP_SHARED creates a\n"
                    + "mapping that's shared with all other processes mapping the same areas of\n"
                    + "the file.  The default value is MAP_SHARED.\n"
                    + "\n"
                    + "To map anonymous memory, pass -1 as the fileno (both versions).\n"
                    + "\n"
                    + "\n"
                    + "If trackfd is False, the file descriptor is not duplicated, and the mmap\n"
                    + "object will not close the underlying file.  The file descriptor must\n"
                    + "remain open for the duration of the mmap object's use.";
}
