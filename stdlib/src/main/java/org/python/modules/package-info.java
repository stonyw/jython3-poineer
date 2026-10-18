/**
 * Standard library assembly: the CPython 3.14 pure-Python subset we ship, plus Jython-specific
 * overrides ({@code *_jy.py}) that win over CPython's copy of the same module name.
 *
 * <p>Mechanism ported from Jython 2.7 ({@code CPythonLib.includes} + "our Lib always wins"); the
 * module list is regenerated for 3.14 in milestone M4.
 */
package org.python.modules;
