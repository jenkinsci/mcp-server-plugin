/*
 *
 * The MIT License
 *
 * Copyright (c) 2026, the Jenkins MCP Server plugin contributors.
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

package io.jenkins.plugins.mcp.server.junit;

import io.jenkins.plugins.mcp.server.Endpoint;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Enables anonymous MCP calls for tests that leave {@code JenkinsRule}'s {@code TestCrumbIssuer} in place.
 *
 * <p>Those clients send no {@code Authorization} header, so {@link Endpoint#ALLOW_UNAUTHENTICATED_MCP} has to be
 * true or {@code TestCrumbIssuer} rejects the POST. The flag is initialized once from
 * {@code io.jenkins.plugins.mcp.server.Endpoint.allowUnauthenticatedMcp}. Surefire starts a new JVM per test class,
 * but a class can also initialize {@link Endpoint} before the test method (for example via a static import), so
 * this sets the system property and the field together, then restores both. Tests that cover the default
 * ({@code false}), including ones that install {@code DefaultCrumbIssuer}, do not use this extension.
 */
public class TestCrumbIssuerMcpExtension implements BeforeEachCallback, AfterEachCallback {

    static final String PROPERTY = Endpoint.class.getName() + ".allowUnauthenticatedMcp";

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(TestCrumbIssuerMcpExtension.class);

    private static final String SAVED = "saved";

    @Override
    public void beforeEach(ExtensionContext context) {
        // Read the field before setting the property. Otherwise the first touch of Endpoint can initialize
        // ALLOW_UNAUTHENTICATED_MCP from the property and leave it true after this test restores the field.
        boolean previousFlag = Endpoint.ALLOW_UNAUTHENTICATED_MCP;
        String previousProperty = System.getProperty(PROPERTY);
        context.getStore(NAMESPACE).put(SAVED, new Saved(previousProperty, previousFlag));
        System.setProperty(PROPERTY, "true");
        Endpoint.ALLOW_UNAUTHENTICATED_MCP = true;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Saved saved = context.getStore(NAMESPACE).remove(SAVED, Saved.class);
        if (saved == null) {
            return;
        }
        if (saved.property == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, saved.property);
        }
        Endpoint.ALLOW_UNAUTHENTICATED_MCP = saved.flag;
    }

    private static final class Saved {
        private final String property;
        private final boolean flag;

        private Saved(String property, boolean flag) {
            this.property = property;
            this.flag = flag;
        }
    }
}
