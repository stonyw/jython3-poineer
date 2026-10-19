package org.python.interop.fixtures;

import java.util.ArrayList;
import java.util.List;

/**
 * A Java surface for the interop tests to be aimed at, with the shapes M2 has to resolve: overloaded
 * constructors and methods, statics, constants, bean properties, public fields, varargs, a functional
 * interface, a generic method, and a method that throws.
 *
 * <p>Nothing here is Jython-aware. That is the point: the expected answer for an interop case is what
 * Java says when the same call is written in Java, and {@code FixtureOracleTest} computes those
 * answers in plain Java so the Python-side assertions are checked rather than remembered.
 */
public class Widget {

    public static final int MAX_SIZE = 9;
    public static final String LABEL = "widget";

    public int size;
    private String name;
    private boolean polished;

    public Widget() {
        this.size = 0;
        this.name = "unnamed";
    }

    public Widget(int size) {
        this.size = size;
        this.name = "sized";
    }

    public Widget(String name, int size) {
        this.size = size;
        this.name = name;
    }

    // --- statics -------------------------------------------------------------------------

    public static String describe(int size) {
        return "widget:" + size;
    }

    public static String describe(String name) {
        return "widget/" + name;
    }

    public static String describe(int size, int depth) {
        return "widget:" + size + "@" + depth;
    }

    public static int widest(Number first, double second) {
        return first.intValue() > second ? first.intValue() : (int) second;
    }

    public static String join(String... parts) {
        return String.join("|", parts);
    }

    public static int count(List<String> names) {
        return names.size();
    }

    // --- bean properties and fields ------------------------------------------------------

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isPolished() {
        return polished;
    }

    public void setPolished(boolean polished) {
        this.polished = polished;
    }

    public int area() {
        return size * size;
    }

    // --- behaviour that has to cross the boundary ----------------------------------------

    public List<String> names() {
        List<String> out = new ArrayList<>();
        out.add("alpha");
        out.add("beta");
        out.add(name);
        return out;
    }

    public String transform(java.util.function.Function<String, String> mapper) {
        return mapper.apply(name);
    }

    public String shout() {
        return name.toUpperCase();
    }

    public String boom() {
        throw new IllegalStateException("widget is broken");
    }

    @Override
    public String toString() {
        return "Widget[" + name + "," + size + "]";
    }

    /** A nested class, because {@code Widget.Builder} has to resolve through the outer name. */
    public static class Builder {
        private int size;

        public Builder size(int size) {
            this.size = size;
            return this;
        }

        public Widget build() {
            return new Widget(size);
        }
    }
}
