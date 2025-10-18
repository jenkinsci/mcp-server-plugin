/*
 *
 * The MIT License
 *
 * Copyright (c) 2025, Gong Yi.
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

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;

public class SlidingWindow<E> {
    private final int maxSize;
    private final Deque<E> deque;

    public SlidingWindow(int maxSize) {
        this.maxSize = maxSize;
        this.deque = new ArrayDeque<>(maxSize);
    }

    public boolean add(E e) {
        if (maxSize == 0) {
            // If maxSize is 0, we don't store any elements
            return false;
        }
        boolean removed = false;
        if (deque.size() == maxSize) {
            removed = true;
            deque.removeFirst();
        }
        deque.addLast(e);
        return removed;
    }

    public E remove() {
        return deque.removeFirst();
    }

    public int size() {
        return deque.size();
    }

    public Collection<E> getRecords() {
        return deque;
    }

    public boolean isEmpty() {
        return deque.isEmpty();
    }

    @Override
    public String toString() {
        return deque.toString();
    }
}
