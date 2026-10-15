/*
 *
 * The MIT License
 *
 * Copyright (c) 2026, Omar Mahamid
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

package io.jenkins.plugins.mcp.server.apps;

import hudson.Functions;
import java.util.List;

final class McpAppPage {

    static final String HEAD_SLOT = "<meta name=\"jenkins:head\">";

    private McpAppPage() {}

    static String assemble(String builtPage, String view, List<String> callableTools, JenkinsLook look) {
        return builtPage.replace(
                HEAD_SLOT,
                "<meta name=\"jenkins:view\" content=\"" + Functions.htmlAttributeEscape(view) + "\">"
                        + "<meta name=\"jenkins:callable-tools\" content=\""
                        + Functions.htmlAttributeEscape(String.join(" ", callableTools)) + "\">"
                        + look.asHeadMarkup());
    }
}
