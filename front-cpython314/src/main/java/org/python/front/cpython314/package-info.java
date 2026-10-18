/**
 * Stage A front-end: reads CPython 3.14 {@code marshal} streams and interprets unspecialized wordcode.
 *
 * <p>Everything CPython-3.14-specific is quarantined here so the native front-end (milestone M5) can
 * replace it without touching the runtime.
 */
package org.python.front.cpython314;
