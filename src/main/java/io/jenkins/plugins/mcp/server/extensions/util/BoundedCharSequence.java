/*
 *
 * The MIT License
 *
 * Copyright (c) 2026, Olivier Lamy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package io.jenkins.plugins.mcp.server.extensions.util;

/**
 * Text that gives up once it has been read a given number of times.
 *
 * <p>Handy for putting a ceiling on a regex match: the matcher reads every character it compares
 * through {@link #charAt(int)}, so counting those is a cheap way to cap the work.
 *
 * <pre>
 * try {
 *     return pattern.matcher(new BoundedCharSequence(line, budget)).find();
 * } catch (BoundedCharSequence.BudgetExceededException e) {
 *     // gave up on this line
 * }
 * </pre>
 *
 * <p>Use one per piece of text, from a single thread.
 */
public final class BoundedCharSequence implements CharSequence {

    /** Thrown when the budget runs out. No stack trace, as nothing looks at it. */
    public static class BudgetExceededException extends RuntimeException {

        private final long budget;

        public BudgetExceededException(long budget) {
            super("Gave up matching after " + budget + " character reads", null, false, false);
            this.budget = budget;
        }

        public long getBudget() {
            return budget;
        }
    }

    private final CharSequence delegate;
    private final long budget;
    private long reads;

    /**
     * @param delegate the text to read
     * @param budget how many reads to allow; must be positive
     */
    public BoundedCharSequence(CharSequence delegate, long budget) {
        if (budget <= 0) {
            throw new IllegalArgumentException("budget must be positive, was " + budget);
        }
        this.delegate = delegate;
        this.budget = budget;
    }

    /** How many reads have been used so far. */
    public long getReads() {
        return reads;
    }

    @Override
    public char charAt(int index) {
        if (++reads > budget) {
            throw new BudgetExceededException(budget);
        }
        return delegate.charAt(index);
    }

    @Override
    public int length() {
        return delegate.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        // Carry the remaining budget over, so slicing cannot start again from zero.
        return new BoundedCharSequence(delegate.subSequence(start, end), Math.max(1, budget - reads));
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
