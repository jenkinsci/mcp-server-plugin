/*
 *
 * The MIT License
 *
 * Copyright (c) 2025, Derek Taubert.
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

package io.jenkins.plugins.mcp.server.extensions;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Simple compilation test to verify that our BuildArtifactsExtension compiles correctly.
 * This test doesn't require the full Jenkins test harness.
 */
public class BuildArtifactsExtensionCompileTest {

    @Test
    void testBuildArtifactsExtensionCanBeInstantiated() {
        BuildArtifactsExtension extension = new BuildArtifactsExtension();
        assertNotNull(extension);
    }

    @Test
    void testBuildArtifactResponseRecord() {
        BuildArtifactsExtension.BuildArtifactResponse response = 
            new BuildArtifactsExtension.BuildArtifactResponse(false, 100L, "test content");
        
        assertNotNull(response);
        assert !response.hasMoreContent();
        assert response.totalSize() == 100L;
        assert "test content".equals(response.content());
    }
}
