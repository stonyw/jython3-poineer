package org.python.front.cpython314;

/** A marshal stream that is truncated, self-inconsistent or from a format this reader is not. */
public class MarshalFormatException extends RuntimeException {

    MarshalFormatException(String message) {
        super(message);
    }

    static MarshalFormatException truncated() {
        return new MarshalFormatException("truncated marshal data (EOF read where not expected)");
    }

    static MarshalFormatException badData(String what) {
        return new MarshalFormatException("bad marshal data (" + what + ")");
    }
}
