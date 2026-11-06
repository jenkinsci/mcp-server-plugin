package io.jenkins.plugins.mcp.server.extensions.util;

import static org.assertj.core.api.Assertions.*;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class BoundedCharSequenceTest {

    @Test
    void delegatesLengthAndCharAt() {
        BoundedCharSequence seq = new BoundedCharSequence("hello", 100);

        assertThat(seq.length()).isEqualTo(5);
        assertThat(seq.charAt(0)).isEqualTo('h');
        assertThat(seq.charAt(4)).isEqualTo('o');
        assertThat(seq).hasToString("hello");
    }

    @Test
    void countsOnlyCharacterReads() {
        BoundedCharSequence seq = new BoundedCharSequence("hello", 100);

        assertThat(seq.getReads()).isZero();
        seq.length(); // length is free, it does no scanning
        assertThat(seq.getReads()).isZero();

        seq.charAt(0);
        seq.charAt(1);
        assertThat(seq.getReads()).isEqualTo(2);
    }

    @Test
    void throwsOnceTheBudgetIsSpent() {
        BoundedCharSequence seq = new BoundedCharSequence("abcdef", 3);

        seq.charAt(0);
        seq.charAt(1);
        seq.charAt(2);
        assertThat(seq.getReads()).isEqualTo(3);

        assertThatThrownBy(() -> seq.charAt(3))
                .isInstanceOf(BoundedCharSequence.BudgetExceededException.class)
                .hasMessageContaining("3")
                .extracting(e -> ((BoundedCharSequence.BudgetExceededException) e).getBudget())
                .isEqualTo(3L);
    }

    @Test
    void rejectsANonPositiveBudget() {
        assertThatThrownBy(() -> new BoundedCharSequence("x", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedCharSequence("x", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void subSequenceKeepsSpendingTheSameBudget() {
        BoundedCharSequence seq = new BoundedCharSequence("abcdef", 4);
        seq.charAt(0);
        seq.charAt(1);

        // Two reads are already gone, so the slice may only make two more before it gives up.
        CharSequence slice = seq.subSequence(2, 6);
        slice.charAt(0);
        slice.charAt(1);

        assertThatThrownBy(() -> slice.charAt(2)).isInstanceOf(BoundedCharSequence.BudgetExceededException.class);
    }

    @Test
    void boundsACatastrophicallyBacktrackingPattern() {
        // (.*,){11}P is ten characters and never matches this subject. Unbounded, java.util.regex
        // explores exponentially many paths: tens of seconds for a line of a few dozen characters,
        // and it only gets worse as the line grows. Bounded, it gives up.
        Pattern hostile = Pattern.compile("(.*,){11}P");
        String line = "a,".repeat(60) + "X";

        BoundedCharSequence bounded = new BoundedCharSequence(line, 2_000_000);
        assertThatThrownBy(() -> hostile.matcher(bounded).find())
                .isInstanceOf(BoundedCharSequence.BudgetExceededException.class);
        assertThat(bounded.getReads()).isGreaterThan(2_000_000L);
    }

    @Test
    void leavesAnOrdinaryPatternAlone() {
        // A linear pattern costs a couple of reads per character, so it finishes far inside the
        // budget and returns the same answer it would without the wrapper.
        String line = "lorem ipsum dolor sit amet ".repeat(400); // ~10 kB, far longer than a real log line
        BoundedCharSequence bounded = new BoundedCharSequence(line, 10_000_000);

        assertThat(Pattern.compile("dolor\\s+sit").matcher(bounded).find()).isTrue();
        assertThat(bounded.getReads()).isLessThan(100_000L);
    }
}
