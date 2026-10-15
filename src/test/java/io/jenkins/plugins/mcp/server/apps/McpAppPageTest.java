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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpAppPageTest {

    @Test
    void testAssemble() {
        var look = new JenkinsLook(
                ".jenkins-app-bar{display:flex}",
                "data:image/svg+xml;base64,PHN2Zy8+",
                "2.541.3",
                Map.of("status-red", "<svg class=\"icon-xlg\"></svg>"));

        var page = McpAppPage.assemble(
                "<head>" + McpAppPage.HEAD_SLOT + "</head>", "build-log", List.of("getBuildLog", "getBuild"), look);

        assertThat(page)
                .startsWith("<head><meta name=\"jenkins:view\" content=\"build-log\">"
                        + "<meta name=\"jenkins:callable-tools\" content=\"getBuildLog getBuild\">")
                .contains("<meta name=\"jenkins:version\" content=\"2.541.3\">")
                .contains("<style id=\"jenkins-styles\">.jenkins-app-bar{display:flex}</style>")
                .contains("<template id=\"jenkins-logo\"><img src=\"data:image/svg+xml;base64,PHN2Zy8+\"")
                .contains("<template id=\"jenkins-symbol-status-red\"><svg class=\"icon-xlg\"></svg></template>")
                .endsWith("</head>");
    }

    @Test
    void testVersionCanNotBreakOutOfItsAttribute() {
        var look = new JenkinsLook("a{}", "data:,", "2.541\"><script>", Map.of());

        assertThat(look.asHeadMarkup())
                .startsWith("<meta name=\"jenkins:version\" content=\"2.541&quot;&gt;&lt;script&gt;\">");
    }
}
