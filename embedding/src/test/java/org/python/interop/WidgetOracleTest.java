package org.python.interop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.python.interop.fixtures.Widget;

/**
 * The oracle for the interop tests, written in the language the calls are made in.
 *
 * <p>Python cannot be the reference for {@code java.*}: CPython 3.14 has no Java at all, so the
 * differential mechanism that pinned every M1 answer has nothing to compare against here. What does
 * have an opinion is the JVM. Every literal in {@code JavaFromPythonTest} therefore appears here
 * first, produced by an ordinary Java call, and the two files fail together if a literal is wrong.
 * That is the difference between an expectation someone checked and one someone imagined.
 *
 * <p>Also pinned here: the calls the interop layer must NOT resolve, so that a Python-side
 * {@code TypeError} can be shown to be the same judgement Java makes rather than a limitation.
 */
class WidgetOracleTest {

    @Test
    void constructorsAndToStringAgree() {
        assertThat(new Widget().toString()).isEqualTo("Widget[unnamed,0]");
        assertThat(new Widget(7).toString()).isEqualTo("Widget[sized,7]");
        assertThat(new Widget("iron", 3).toString()).isEqualTo("Widget[iron,3]");
        assertThat(new Widget(7).area()).isEqualTo(49);
        assertThat(new Widget("iron", 3).area()).isEqualTo(9);
    }

    @Test
    void fieldsAndBeanPropertiesReadAndWrite() {
        Widget widget = new Widget();
        assertThat(widget.size).isEqualTo(0);
        assertThat(widget.getName()).isEqualTo("unnamed");
        assertThat(widget.isPolished()).isFalse();

        widget.setName("copper");
        widget.setPolished(true);
        assertThat(widget.toString()).isEqualTo("Widget[copper,0]");
        assertThat(widget.isPolished()).isTrue();
    }

    @Test
    void staticMethodsAreChosenByArgumentType() {
        assertThat(Widget.describe(5)).isEqualTo("widget:5");
        assertThat(Widget.describe("iron")).isEqualTo("widget/iron");
        assertThat(Widget.describe(2, 9)).isEqualTo("widget:2@9");
        assertThat(Widget.MAX_SIZE).isEqualTo(9);
        assertThat(Widget.LABEL).isEqualTo("widget");
    }

    /**
     * {@code widest(Number, double)}: an {@code int} literal in Java cannot reach a {@code Number}
     * parameter without boxing, and here it happens without complaint because the JVM boxes it.
     */
    @Test
    void wideningAndBoxingFollowTheJavaWideningOrder() {
        assertThat(Widget.widest(3, 4.5)).isEqualTo(4);
        assertThat(Widget.widest(9, 4.5)).isEqualTo(9);
        assertThat(Widget.widest(Long.valueOf(2), 8.25)).isEqualTo(8);
    }

    @Test
    void varargsAndGenericsAcceptTheObviousArguments() {
        assertThat(Widget.join("a", "b", "c")).isEqualTo("a|b|c");
        assertThat(Widget.join()).isEmpty();
        assertThat(Widget.count(List.of("a", "b"))).isEqualTo(2);
    }

    @Test
    void aJavaListSurvivesAsTheSameThreeStrings() {
        List<String> names = new Widget().names();
        assertThat(names).containsExactly("alpha", "beta", "unnamed");
        assertThat(names.size()).isEqualTo(3);
        assertThat(names.get(0)).isEqualTo("alpha");
    }

    @Test
    void aSamInterfaceAcceptsALambda() {
        assertThat(new Widget().transform(value -> value.toUpperCase()))
                .isEqualTo("UNNAMED");
    }

    @Test
    void nestedClassesBuildThroughChainedStatics() {
        assertThat(new Widget.Builder().size(4).build().toString())
                .isEqualTo("Widget[sized,4]");
    }

    /** The call that must fail in Java fails in Python for the same reason, not a different one. */
    @Test
    void thereIsNoThreeArgumentDescribeToCall() {
        assertThatThrownBy(() -> Widget.class.getMethod("describe", int.class, int.class, int.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void aThrownExceptionCarriesItsOwnMessage() {
        assertThatThrownBy(new Widget()::boom)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("widget is broken");
    }

    @Test
    void theJdkClassesTheInteropTestsReachAnswerAsExpected() {
        ArrayList<String> names = new ArrayList<>();
        names.add("a");
        names.add("b");
        names.add("c");
        assertThat(names).hasSize(3).containsExactly("a", "b", "c");
        assertThat(names.get(0)).isEqualTo("a");
        assertThat(names.get(names.size() - 1)).isEqualTo("c");
        assertThat(names.contains("a")).isTrue();
        assertThat(names.contains("missing")).isFalse();

        // Replacing the first element is what makes the `in` question have to come before it.
        names.set(0, "z");
        assertThat(names.toString()).isEqualTo("[z, b, c]");
        assertThat(names.contains("a")).isFalse();

        HashMap<String, Integer> sizes = new HashMap<>();
        sizes.put("a", 1);
        sizes.put("b", 2);
        assertThat(sizes).hasSize(2);
        assertThat(sizes.get("a")).isEqualTo(1);
        assertThat(new ArrayList<>(sizes.keySet()).stream().sorted().toList())
                .containsExactly("a", "b");
        assertThat(sizes.containsKey("a")).isTrue();
        assertThat(sizes.get("b")).isEqualTo(2);

        assertThat(Integer.parseInt("42")).isEqualTo(42);
        assertThat(Integer.MAX_VALUE).isEqualTo(2147483647);
        assertThat(Math.PI).isEqualTo(3.141592653589793);
        assertThat(Math.max(3, 7)).isEqualTo(7);
    }
}
