package org.python.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * The sentence a report hangs on the end of a complaint about a name nothing holds.
 *
 * <p>{@code TracebackException.__init__} appends this text to its own copy of {@code str(exc)} and never
 * to the exception, so {@code str(e)} stays whatever the arguments said and only the lines the reader is
 * given grow the extra sentence. Two kinds of error are asked — {@code NameError} and {@code
 * AttributeError}, either of them only when the failure left a {@code name} behind — and a {@code
 * NameError} whose name is also the name of a standard library module is given the import it forgot,
 * joined with {@code Or} when a suggestion already filled the first half of the line.
 *
 * <p>The distance is {@code Python/suggestions.c} rather than the Python fallback that follows its import
 * in {@code Lib/traceback.py}, because 3.14 builds the module and the module is what answers. The two are
 * not the same arithmetic: the C chooser counts UTF-8 bytes, refuses a candidate list of 750 names or
 * more, and begins with no best distance to beat at all, where the Python one counts characters, refuses
 * only a list longer than 750, and begins from the length of the wrong name. Which candidate wins a tie
 * is decided by the order the list is handed over, and so by which chooser ran.
 *
 * <p>Where the candidates come from is the other half of the work. A {@code NameError} is answered by the
 * names the innermost frame can see — its locals, then its globals, then the built-ins, unsorted and with
 * duplicates kept, because that is the list CPython builds — except for the one case it settles before any
 * distance is worked out: a method whose instance really does hold the name it read, which is answered
 * {@code self.<name>} however far that spelling is from anything. An {@code AttributeError} is answered by
 * what {@code dir()} would say of the object the read failed on, sorted, and with the names that begin with
 * an underscore hidden unless the wrong name began with one too — unless the frame that made the read is a
 * method of that very object, in which case its private names are shown, because a person writing {@code
 * self._count} has already been given leave to.
 *
 * <p>Four readings are named omissions rather than pretences. This runtime has no import machinery, so no
 * {@code ImportError} ever carries a {@code name_from} and the branch that reads one is not here. A built-in
 * type keeps its methods in a {@link Members} table and its other members in a Java switch, so the
 * properties of the numeric tower — {@code real}, {@code imag} — and the dunders CPython copies into every
 * static type's dictionary are not candidates for a suggestion. The built-ins are listed in the order this
 * runtime registered them, not CPython's, which only shows when two of them tie. And a failure anywhere
 * inside the chooser leaves the sentence out entirely: CPython lets it escape into {@code
 * _PyErr_Display}, which drops the whole report down to the C printer, and the printer this runtime has no
 * equivalent of cannot write a {@code -c} command's source line at all.
 */
final class Suggestions {

    /** {@code MAX_CANDIDATE_ITEMS}: at this many names the chooser gives up rather than choose. */
    private static final int MAX_CANDIDATE_ITEMS = 750;

    /** {@code MAX_STRING_SIZE}: past this many bytes of either name, no distance is worked out. */
    private static final int MAX_STRING_SIZE = 40;

    private static final int MOVE_COST = 2;

    private static final int CASE_COST = 1;

    /** {@code sys.stdlib_module_names}, which is what turns a missing name into a forgotten import. */
    private static final Set<String> STDLIB_MODULES =
            Set.of(
                    ("__future__,_abc,_aix_support,_android_support,_apple_support,_ast,"
                            + "_ast_unparse,_asyncio,_bisect,_blake2,_bz2,_codecs,_codecs_cn,"
                            + "_codecs_hk,_codecs_iso2022,_codecs_jp,_codecs_kr,_codecs_tw,"
                            + "_collections,_collections_abc,_colorize,_compat_pickle,_contextvars,"
                            + "_csv,_ctypes,_curses,_curses_panel,_datetime,_dbm,_decimal,_elementtree,"
                            + "_frozen_importlib,_frozen_importlib_external,_functools,_gdbm,_hashlib,"
                            + "_heapq,_hmac,_imp,_interpchannels,_interpqueues,_interpreters,_io,"
                            + "_ios_support,_json,_locale,_lsprof,_lzma,_markupbase,_md5,"
                            + "_multibytecodec,_multiprocessing,_opcode,_opcode_metadata,_operator,"
                            + "_osx_support,_overlapped,_pickle,_posixshmem,_posixsubprocess,_py_abc,"
                            + "_py_warnings,_pydatetime,_pydecimal,_pyio,_pylong,_pyrepl,_queue,"
                            + "_random,_remote_debugging,_scproxy,_sha1,_sha2,_sha3,_signal,"
                            + "_sitebuiltins,_socket,_sqlite3,_sre,_ssl,_stat,_statistics,_string,"
                            + "_strptime,_struct,_suggestions,_symtable,_sysconfig,_thread,"
                            + "_threading_local,_tkinter,_tokenize,_tracemalloc,_types,_typing,_uuid,"
                            + "_warnings,_weakref,_weakrefset,_winapi,_wmi,_zoneinfo,_zstd,abc,"
                            + "annotationlib,antigravity,argparse,array,ast,asyncio,atexit,base64,bdb,"
                            + "binascii,bisect,builtins,bz2,cProfile,calendar,cmath,cmd,code,codecs,"
                            + "codeop,collections,colorsys,compileall,compression,concurrent,"
                            + "configparser,contextlib,contextvars,copy,copyreg,csv,ctypes,curses,"
                            + "dataclasses,datetime,dbm,decimal,difflib,dis,doctest,email,encodings,"
                            + "ensurepip,enum,errno,faulthandler,fcntl,filecmp,fileinput,fnmatch,"
                            + "fractions,ftplib,functools,gc,genericpath,getopt,getpass,gettext,glob,"
                            + "graphlib,grp,gzip,hashlib,heapq,hmac,html,http,idlelib,imaplib,"
                            + "importlib,inspect,io,ipaddress,itertools,json,keyword,linecache,locale,"
                            + "logging,lzma,mailbox,marshal,math,mimetypes,mmap,modulefinder,msvcrt,"
                            + "multiprocessing,netrc,nt,ntpath,nturl2path,numbers,opcode,operator,"
                            + "optparse,os,pathlib,pdb,pickle,pickletools,pkgutil,platform,plistlib,"
                            + "poplib,posix,posixpath,pprint,profile,pstats,pty,pwd,py_compile,pyclbr,"
                            + "pydoc,pydoc_data,pyexpat,queue,quopri,random,re,readline,reprlib,"
                            + "resource,rlcompleter,runpy,sched,secrets,select,selectors,shelve,shlex,"
                            + "shutil,signal,site,smtplib,socket,socketserver,sqlite3,sre_compile,"
                            + "sre_constants,sre_parse,ssl,stat,statistics,string,stringprep,struct,"
                            + "subprocess,symtable,sys,sysconfig,syslog,tabnanny,tarfile,tempfile,"
                            + "termios,textwrap,this,threading,time,timeit,tkinter,token,tokenize,"
                            + "tomllib,trace,traceback,tracemalloc,tty,turtle,turtledemo,types,typing,"
                            + "unicodedata,unittest,urllib,uuid,venv,warnings,wave,weakref,webbrowser,"
                            + "winreg,winsound,wsgiref,xml,xmlrpc,zipapp,zipfile,zipimport,zlib,"
                            + "zoneinfo")
                            .split(","));

    private Suggestions() {
    }

    /**
     * The text the report adds to {@code str(value)}: {@code . Did you mean: 'x'?}, the forgotten import,
     * or the two of them joined, or nothing at all.
     *
     * @param innermost the frame the traceback ends at, which is the one a {@code NameError} is asked of
     */
    static String sentence(PyBaseException value, Frame innermost) {
        PyType kind = value.exceptionType();
        // CPython asks ImportError with a name_from first; this runtime never sets one, so no error
        // arrives here that would answer to that branch.
        boolean nameError = kind.matches(ExceptionTypes.NAME_ERROR);
        if (!nameError && !kind.matches(ExceptionTypes.ATTRIBUTE_ERROR)) {
            return null;
        }
        if (!(value.failureName() instanceof PyStr named)) {
            // getattr(exc_value, "name", None) is not None, and the chooser then wants a str: an error
            // that named nothing, or that was handed something that is not text, is left alone.
            return null;
        }
        String wrongName = named.getValue();
        String suggestion =
                nameError
                        ? forMissingName(innermost, wrongName)
                        : forFailedRead(value.failureObject(), wrongName, innermost);
        if (suggestion != null && suggestion.isEmpty()) {
            // Every test of the result is CPython's "if suggestion:", which an empty candidate would fail.
            suggestion = null;
        }
        boolean forgotten = nameError && STDLIB_MODULES.contains(wrongName);
        if (suggestion == null && !forgotten) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        if (suggestion != null) {
            text.append(". Did you mean: '").append(suggestion).append("'?");
        }
        if (forgotten) {
            text.append(
                            suggestion == null
                                    ? ". Did you forget to import '"
                                    : " Or did you forget to import '")
                    .append(wrongName)
                    .append("'?");
        }
        return text.toString();
    }

    // --- the candidates ------------------------------------------------------------------

    /**
     * The name a missing local, global or built-in was reaching for.
     *
     * <p>No traceback, no sentence: {@code _compute_suggestion_error} returns as soon as it finds that
     * {@code tb} is {@code None}, because the names it would choose among are the ones the frame that
     * failed could see. {@code format_exception_only} is that call, which is why a complaint printed on
     * its own never suggests anything.
     */
    private static String forMissingName(Frame innermost, String wrongName) {
        if (innermost == null) {
            return null;
        }
        PyDict locals = innermost.localValues();
        List<String> names = new ArrayList<>();
        collect(names, locals.keys());
        collect(names, innermost.globals().keys());
        collect(names, PyRuntime.builtins().keys());
        // The receiver of a method answers a bare name before any distance is worked out — and with the
        // dotted spelling, which no candidate list holds.
        PyObject self = locals.get(PyStr.interned("self"));
        if (self != null && holds(self, wrongName)) {
            return "self." + wrongName;
        }
        return closest(names, wrongName);
    }

    /**
     * The name a member read was reaching for, chosen from the names the object answers {@code dir()} with.
     *
     * <p>The underscore rule is CPython's in both directions: a wrong name that does not begin with one is
     * not answered by a candidate that does, unless the frame the error came from is a method of this very
     * object — {@code frame.f_locals['self'] is obj} — because inside the class that owns a private name,
     * hiding it would hide the only answer that fits.
     */
    private static String forFailedRead(PyObject target, String wrongName, Frame innermost) {
        if (target == null) {
            // An AttributeError with no obj: CPython's read of the member escapes the chooser, and this
            // runtime has nothing to fall back to either.
            return null;
        }
        List<String> names;
        try {
            names = safeDir(target);
        } catch (RuntimeException refused) {
            return null;
        }
        boolean hideUnderscored = !wrongName.startsWith("_");
        if (hideUnderscored && innermost != null && holdsSelf(innermost, target)) {
            hideUnderscored = false;
        }
        if (hideUnderscored) {
            names.removeIf(name -> name.startsWith("_"));
        }
        return closest(names, wrongName);
    }

    /** Whether the frame's own {@code self} is the very object the read failed on. */
    private static boolean holdsSelf(Frame innermost, PyObject target) {
        PyObject self = innermost.localValues().get(PyStr.interned("self"));
        return self != null && self == target;
    }

    /**
     * {@code _get_safe___dir__}: the names the value answers {@code dir()} with, sorted, each of them text.
     *
     * <p>A class that writes its own {@code __dir__} is asked for it, since that is the member CPython
     * calls. A value whose type declares none is answered by {@link Dirs#defaultNames}, the walk of
     * {@code object.__dir__} and {@code type.__dir__}. CPython's {@code except TypeError} retry calls
     * {@code type(obj).__dir__(obj)}, a walk of those same tables, so a {@code __dir__} that refuses on the
     * number of its arguments lands there too — the one case where that differs is a {@code __dir__} that
     * wants no receiver at all, where CPython's retry fails the same way and the report is left with no
     * sentence.
     */
    private static List<String> safeDir(PyObject target) {
        PyObject read;
        try {
            read = Slots.getAttr(target, "__dir__");
        } catch (PyRaise withoutDir) {
            if (isMissingDir(withoutDir)) {
                return sorted(Dirs.defaultNames(target));
            }
            throw withoutDir;
        }
        PyObject listed;
        try {
            listed = Slots.call(read, new PyObject[0], null);
        } catch (PyRaise refused) {
            if (!refused.value().exceptionType().matches(ExceptionTypes.TYPE_ERROR)) {
                throw refused;
            }
            return sorted(Dirs.defaultNames(target));
        }
        List<String> names = new ArrayList<>();
        PyIterator walker = Slots.iterator(listed);
        for (PyObject item = walker.nextOrNull(); item != null; item = walker.nextOrNull()) {
            if (item instanceof PyStr text) {
                names.add(text.getValue());
            }
        }
        return sorted(names);
    }

    /** Whether a refusal is the read of {@code __dir__} itself finding nothing, rather than something else. */
    private static boolean isMissingDir(PyRaise refusal) {
        PyBaseException value = refusal.value();
        return value.exceptionType().matches(ExceptionTypes.ATTRIBUTE_ERROR)
                && "__dir__".equals(nameOf(value));
    }

    /** The name a failed read left behind, or null where it left none. */
    private static String nameOf(PyBaseException value) {
        return value.failureName() instanceof PyStr named ? named.getValue() : null;
    }

    /** The keys that are text, added in the order they were given: {@code [x for x in d if isinstance(x, str)]}. */
    private static void collect(List<String> names, PyObject[] keys) {
        for (PyObject key : keys) {
            if (key instanceof PyStr text) {
                names.add(text.getValue());
            }
        }
    }

    /** {@code sorted(x for x in d if isinstance(x, str))}: code-point order, which {@code dir()} answers in. */
    private static List<String> sorted(List<String> names) {
        List<String> order = new ArrayList<>(names);
        order.sort(Suggestions::compareCodePoints);
        return order;
    }

    /**
     * CPython's order on text, which compares code points rather than the UTF-16 units a Java string holds.
     *
     * <p>The two differ only where a name carries both an emoji and a character above {@code U+E000}, and a
     * candidate list is sorted before it is walked, so a tie between two of its entries is settled here.
     */
    private static int compareCodePoints(String left, String right) {
        int[] a = left.codePoints().toArray();
        int[] b = right.codePoints().toArray();
        for (int i = 0; i < a.length && i < b.length; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return Integer.compare(a.length, b.length);
    }

    /** Whether a value really does hold that name, as far as {@code hasattr} is willing to say. */
    private static boolean holds(PyObject target, String name) {
        try {
            return Slots.getAttr(target, name) != null;
        } catch (RuntimeException refused) {
            // CPython's except Exception around the hasattr: a value that complains while being read has
            // not the attribute, as far as the report is concerned.
            return false;
        }
    }

    // --- the chooser ---------------------------------------------------------------------

    /**
     * {@code _Py_CalculateSuggestions}: the candidate nearest the name that was not there.
     *
     * <p>First past the post wins a tie, because the chooser only takes a candidate that <em>beats</em> the
     * best distance it has, and the budget it works to is tightened by that best as it goes. A candidate
     * equal to the wrong name is skipped rather than suggested — the name the read missed is exactly the one
     * a list that holds it would otherwise hand back.
     */
    private static String closest(List<String> candidates, String wrongName) {
        if (candidates.size() >= MAX_CANDIDATE_ITEMS) {
            return null;
        }
        byte[] name = utf8(wrongName);
        String best = null;
        long bestDistance = Long.MAX_VALUE;
        long[] buffer = new long[MAX_STRING_SIZE];
        for (String candidate : candidates) {
            byte[] item = utf8(candidate);
            if (Arrays.equals(name, item)) {
                continue;
            }
            // No more than a third of the characters involved may need changed.
            long maxDistance = (long) (name.length + item.length + 3) * MOVE_COST / 6;
            maxDistance = Math.min(maxDistance, bestDistance - 1);
            long distance = levenshtein(name, item, maxDistance, buffer);
            if (distance > maxDistance) {
                continue;
            }
            if (best == null || distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * {@code levenshtein_distance}: the cost of turning one name into the other, or {@code maxCost + 1} for
     * a cost too dear to be worth working out.
     *
     * <p>Three shortcuts come before the arithmetic, and they are why the answer is not simply a
     * Levenshtein distance: the common affixes of the two names are cut off first, so a name of forty bytes
     * and one of forty-one are compared on two bytes rather than refused for length; a name that is the
     * other with something added or removed costs two per byte of the difference and never reaches the table
     * at all; and a row whose cheapest entry already exceeds the budget abandons the rest of the distance.
     */
    private static long levenshtein(byte[] name, byte[] candidate, long maxCost, long[] buffer) {
        int aFrom = 0;
        int bFrom = 0;
        int aSize = name.length;
        int bSize = candidate.length;
        while (aSize > 0 && bSize > 0 && name[aFrom] == candidate[bFrom]) {
            aFrom++;
            bFrom++;
            aSize--;
            bSize--;
        }
        while (aSize > 0 && bSize > 0 && name[aFrom + aSize - 1] == candidate[bFrom + bSize - 1]) {
            aSize--;
            bSize--;
        }
        if (aSize == 0 || bSize == 0) {
            return (long) (aSize + bSize) * MOVE_COST;
        }
        byte[] a = Arrays.copyOfRange(name, aFrom, aFrom + aSize);
        byte[] b = Arrays.copyOfRange(candidate, bFrom, bFrom + bSize);
        if (a.length > MAX_STRING_SIZE || b.length > MAX_STRING_SIZE) {
            return maxCost + 1;
        }
        // Prefer the shorter buffer: the row is as long as the first name, so the swap makes it the shorter
        // of the two and leaves the other to be walked a byte at a time.
        if (b.length < a.length) {
            byte[] swap = a;
            a = b;
            b = swap;
        }
        if ((long) (b.length - a.length) * MOVE_COST > maxCost) {
            return maxCost + 1;
        }
        long step = MOVE_COST;
        for (int i = 0; i < a.length; i++) {
            buffer[i] = step;
            step += MOVE_COST;
        }
        long result = 0;
        for (int bIndex = 0; bIndex < b.length; bIndex++) {
            int code = b[bIndex] & 0xFF;
            long distance = result = (long) bIndex * MOVE_COST;
            long minimum = Long.MAX_VALUE;
            for (int index = 0; index < a.length; index++) {
                long substitute = distance + substitutionCost(code, a[index] & 0xFF);
                distance = buffer[index];
                long insertDelete = Math.min(result, distance) + MOVE_COST;
                result = Math.min(insertDelete, substitute);
                buffer[index] = result;
                minimum = Math.min(minimum, result);
            }
            if (minimum > maxCost) {
                return maxCost + 1;
            }
        }
        return result;
    }

    /**
     * {@code substitution_cost}, on bytes rather than characters: two names that differ only in the five
     * low bits of a byte are the same letter, and a case flip is cheaper than a move.
     */
    private static int substitutionCost(int left, int right) {
        if ((left & 31) != (right & 31)) {
            return MOVE_COST;
        }
        if (left == right) {
            return 0;
        }
        int a = left >= 'A' && left <= 'Z' ? left + 32 : left;
        int b = right >= 'A' && right <= 'Z' ? right + 32 : right;
        return a == b ? CASE_COST : MOVE_COST;
    }

    /** The bytes the distance is counted in: UTF-8, as CPython counts it, not the two-byte units of Java. */
    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
