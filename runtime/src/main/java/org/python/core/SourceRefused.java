package org.python.core;

/**
 * The compiler refused the source: the reference interpreter's report, plus the fields the exception it
 * raised carries.
 *
 * <p>{@code compile()} in CPython raises {@code SyntaxError}, and a program that writes
 * {@code try: compile(bad) except SyntaxError:} expects to catch it. A Java failure crossing the seam
 * would be invisible to that handler, so the compiler hands over the fields instead and this class turns
 * them into the same Python value: {@code msg}, {@code filename}, {@code lineno}, {@code offset},
 * {@code text}, {@code end_lineno} and {@code end_offset}, exactly as the reference interpreter filled
 * them.
 *
 * <p>{@link #report()} is kept apart because it is a different audience. It is the report CPython would
 * print for the script, caret and all, which the launcher quotes verbatim at top level; the fields are
 * for the exception a Python frame catches.
 */
public class SourceRefused extends RuntimeException {

    /** What {@code lineno} and its neighbours answer when the reference interpreter left them {@code None}. */
    public static final long ABSENT = Long.MIN_VALUE;

    private final String report;
    private final String errorType;
    private final String message;
    private final String fileName;
    private final long lineno;
    private final long offset;
    private final String text;
    private final long endLineno;
    private final long endOffset;

    public SourceRefused(
            String report,
            String errorType,
            String message,
            String fileName,
            long lineno,
            long offset,
            String text,
            long endLineno,
            long endOffset) {
        super(report == null ? null : report.strip(), null, false, false);
        this.report = report;
        this.errorType = errorType;
        this.message = message;
        this.fileName = fileName;
        this.lineno = lineno;
        this.offset = offset;
        this.text = text;
        this.endLineno = endLineno;
        this.endOffset = endOffset;
    }

    /** The report CPython would have printed, for the launcher to quote at top level. */
    public String report() {
        return report;
    }

    /**
     * The Python exception this refusal is, ready to be raised.
     *
     * <p>The class is chosen by the name the reference interpreter reported, so an indentation problem
     * arrives as {@code IndentationError} and is caught by the same clause CPython's is. A refusal
     * with no position is {@code SyntaxError(msg)}; one with a position keeps the six-field info tuple,
     * which is what {@code e.args} answers to the tests.
     */
    public PyRaise python() {
        PyType type =
                switch (errorType == null ? "" : errorType) {
                    case "IndentationError" -> ExceptionTypes.INDENTATION_ERROR;
                    case "TabError" -> ExceptionTypes.TAB_ERROR;
                    case "ValueError" -> ExceptionTypes.VALUE_ERROR;
                    case "TypeError" -> ExceptionTypes.TYPE_ERROR;
                    default -> ExceptionTypes.SYNTAX_ERROR;
                };
        String sentence = message == null ? "" : message;
        if (type == ExceptionTypes.VALUE_ERROR
                || type == ExceptionTypes.TYPE_ERROR
                || lineno == ABSENT) {
            return PyRaise.of(
                    new PyBaseException(type, new PyObject[] {PyStr.of(sentence)}));
        }
        PyObject[] info = {
            fileName == null ? PyNone.INSTANCE : PyStr.of(fileName),
            number(lineno),
            number(offset),
            text == null ? PyNone.INSTANCE : PyStr.of(text),
            number(endLineno),
            number(endOffset),
        };
        PyBaseException failure =
                new PyBaseException(type, new PyObject[] {PyStr.of(sentence), PyTuple.of(info)});
        failure.writeMemberField("msg", PyStr.of(sentence));
        failure.writeMemberField("filename", info[0]);
        failure.writeMemberField("lineno", info[1]);
        failure.writeMemberField("offset", info[2]);
        failure.writeMemberField("text", info[3]);
        failure.writeMemberField("end_lineno", info[4]);
        failure.writeMemberField("end_offset", info[5]);
        return PyRaise.of(failure);
    }

    private static PyObject number(long value) {
        return value == ABSENT ? PyNone.INSTANCE : PyInt.of(value);
    }
}
