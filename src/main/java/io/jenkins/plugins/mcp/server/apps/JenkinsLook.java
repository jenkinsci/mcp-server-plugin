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
import jakarta.servlet.ServletContext;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.SneakyThrows;
import org.jenkins.ui.symbol.Symbol;
import org.jenkins.ui.symbol.SymbolRequest;

record JenkinsLook(String stylesheet, String logo, String version, Map<String, String> symbols) {

    static final List<String> STATUS_SYMBOLS = List.of(
            "status-blue",
            "status-red",
            "status-yellow",
            "status-aborted",
            "status-nobuilt",
            "status-disabled",
            "status-blue-anime",
            "status-red-anime",
            "status-yellow-anime",
            "status-aborted-anime",
            "status-nobuilt-anime",
            "status-disabled-anime");

    static final List<String> BUTTON_SYMBOLS = List.of("terminal", "external");

    private static final Pattern RELATIVE_IMAGE_URL = Pattern.compile("url\\((['\"]?)\\.\\./(images/[^)'\"]+)\\1\\)");

    static JenkinsLook readFrom(ServletContext context, String version) {
        var stylesheet = new String(read(context, "/jsbundles/styles.css"), StandardCharsets.UTF_8);
        var symbols = new LinkedHashMap<String, String>();
        STATUS_SYMBOLS.forEach(name -> symbols.put(name, symbol(name, "icon-xlg")));
        BUTTON_SYMBOLS.forEach(name -> symbols.put(name, symbol(name, null)));
        return new JenkinsLook(
                RELATIVE_IMAGE_URL
                        .matcher(stylesheet)
                        .replaceAll(match ->
                                Matcher.quoteReplacement("url(" + dataUri(context, "/" + match.group(2)) + ")")),
                dataUri(context, "/images/svgs/logo.svg"),
                version,
                symbols);
    }

    String asHeadMarkup() {
        var markup = new StringBuilder()
                .append("<meta name=\"jenkins:version\" content=\"")
                .append(Functions.htmlAttributeEscape(this.version))
                .append("\">")
                .append("<style id=\"jenkins-styles\">")
                .append(this.stylesheet)
                .append("</style>")
                .append("<template id=\"jenkins-logo\"><img src=\"")
                .append(this.logo)
                .append("\" aria-hidden=\"true\" id=\"jenkins-head-icon\" alt=\"\"></template>");
        this.symbols.forEach((name, svg) -> markup.append("<template id=\"jenkins-symbol-")
                .append(name)
                .append("\">")
                .append(svg)
                .append("</template>"));
        return markup.toString();
    }

    private static String symbol(String name, String classes) {
        return Symbol.get(
                new SymbolRequest.Builder().withName(name).withClasses(classes).build());
    }

    private static String dataUri(ServletContext context, String path) {
        return "data:" + context.getMimeType(path) + ";base64,"
                + Base64.getEncoder().encodeToString(read(context, path));
    }

    @SneakyThrows
    private static byte[] read(ServletContext context, String path) {
        try (InputStream in = context.getResourceAsStream(path)) {
            return Objects.requireNonNull(in, "The Jenkins web app has no " + path)
                    .readAllBytes();
        }
    }
}
