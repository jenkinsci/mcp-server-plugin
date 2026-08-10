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

package io.jenkins.plugins.mcp.server.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the log tools still work where the Pipeline plugins are <em>not</em> installed. {@code
 * workflow-api} is optional, so a Pipeline type in a position the JVM resolves at class-load time would
 * make a Pipeline-less controller lose {@code getBuildLog} entirely — not just the {@code nodeId}
 * feature. Checking imports by eye is not sufficient.
 *
 * <p>The harness always has {@code workflow-job} on the classpath, so this hides {@code
 * org.jenkinsci.plugins.workflow.**} behind a classloader. No {@code JenkinsRule}.
 */
class BuildLogsWithoutPipelineTest {

    private static final String HIDDEN_PACKAGE = "org.jenkinsci.plugins.workflow.";

    private static final String BUILD_LOGS = "io.jenkins.plugins.mcp.server.extensions.BuildLogsExtension";
    private static final String LOG_SOURCE = "io.jenkins.plugins.mcp.server.extensions.util.LogSource";
    private static final String PIPELINE_UTIL = "io.jenkins.plugins.mcp.server.extensions.util.PipelineLogUtil";
    private static final String GRAPH_EXTENSION = "io.jenkins.plugins.mcp.server.extensions.PipelineGraphExtension";

    /**
     * Loads this plugin's classes itself so their references re-resolve here, delegates everything else
     * to the parent, and reports {@code org.jenkinsci.plugins.workflow.**} as missing.
     */
    private static final class NoPipelineClassLoader extends ClassLoader {

        NoPipelineClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(HIDDEN_PACKAGE)) {
                throw new ClassNotFoundException(name + " (hidden: simulating a controller without Pipeline)");
            }
            if (name.startsWith("io.jenkins.plugins.mcp.")) {
                Class<?> already = findLoadedClass(name);
                if (already != null) {
                    return already;
                }
                synchronized (getClassLoadingLock(name)) {
                    already = findLoadedClass(name);
                    if (already != null) {
                        return already;
                    }
                    byte[] bytes = readClassBytes(name);
                    if (bytes != null) {
                        Class<?> defined = defineClass(name, bytes, 0, bytes.length);
                        if (resolve) {
                            resolveClass(defined);
                        }
                        return defined;
                    }
                }
            }
            return super.loadClass(name, resolve);
        }

        private byte[] readClassBytes(String name) {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(resource)) {
                return in == null ? null : in.readAllBytes();
            } catch (IOException e) {
                return null;
            }
        }
    }

    // -----------------------------------------------------------------------

    @Test
    void buildLogsExtensionLoadsAndInitialisesWithoutPipelinePlugins() throws Exception {
        var loader = new NoPipelineClassLoader(getClass().getClassLoader());

        // initialize=true matters: a lazily-failing <clinit> would slip past otherwise.
        assertThatCode(() -> Class.forName(BUILD_LOGS, true, loader))
                .as("BuildLogsExtension must load without Pipeline; a failure here means a "
                        + "Pipeline-less controller loses getBuildLog entirely")
                .doesNotThrowAnyException();

        Class<?> extension = Class.forName(BUILD_LOGS, true, loader);
        assertThat(extension.getClassLoader()).isSameAs(loader);

        assertThatCode(() -> extension.getDeclaredConstructor().newInstance()).doesNotThrowAnyException();
    }

    @Test
    void toolMethodSignaturesResolveWithoutPipelinePlugins() throws Exception {
        var loader = new NoPipelineClassLoader(getClass().getClassLoader());
        Class<?> extension = Class.forName(BUILD_LOGS, true, loader);

        // Endpoint.resolveTools iterates getMethods() during registration, which resolves every
        // parameter and return type.
        assertThatCode(extension::getMethods)
                .as("a Pipeline type in a method signature would break tool registration")
                .doesNotThrowAnyException();

        List<String> toolMethods = Arrays.stream(extension.getMethods())
                .filter(m -> m.getDeclaringClass().equals(extension))
                .map(Method::getName)
                .filter(n -> n.equals("getBuildLog") || n.equals("searchBuildLog"))
                .toList();
        assertThat(toolMethods).contains("getBuildLog", "searchBuildLog");
    }

    @Test
    void logSourceLoadsWithoutPipelinePlugins() throws Exception {
        var loader = new NoPipelineClassLoader(getClass().getClassLoader());

        assertThatCode(() -> Class.forName(LOG_SOURCE, true, loader)).doesNotThrowAnyException();

        Class<?> logSource = Class.forName(LOG_SOURCE, true, loader);
        assertThatCode(logSource::getMethods).doesNotThrowAnyException();
    }

    @Test
    void classLoaderReallyHidesPipeline() {
        var loader = new NoPipelineClassLoader(getClass().getClassLoader());

        // Sanity check on the harness: if Pipeline were reachable, every assertion here is vacuous.
        assertThatThrownBy(() -> Class.forName(GRAPH_EXTENSION, true, loader))
                .as("PipelineGraphExtension names Pipeline types in its signatures, so it must fail here")
                .isInstanceOf(NoClassDefFoundError.class);
    }

    @Test
    void pipelineHelperLoadsButFailsOnlyWhenCalled() throws Exception {
        var loader = new NoPipelineClassLoader(getClass().getClassLoader());

        // Constant-pool entries resolve lazily, so this loads cleanly with workflow-api absent -- its
        // Pipeline types are only in method bodies. The failure is deferred to the first instruction of
        // resolveNodeLogSource (`instanceof FlowExecutionOwner$Executable`) and is a NoClassDefFoundError,
        // an Error that McpToolWrapper's `catch (Exception)` cannot catch. Hence the by-name guard.
        Class<?> util = Class.forName(PIPELINE_UTIL, true, loader);
        assertThat(util.getClassLoader()).isSameAs(loader);

        // Member reflection does fail, because private findNode(FlowExecution, String) names a
        // Pipeline type. Harmless: Jenkins only reflects over @Extension classes, and this is a utility.
        assertThatThrownBy(util::getDeclaredMethods)
                .as("a private helper's signature names FlowExecution, so member reflection resolves it")
                .isInstanceOf(NoClassDefFoundError.class);

        assertThatCode(() -> util.getMethod("resolveNodeLogSource", hudson.model.Run.class, String.class))
                .as("the public API must stay free of Pipeline types in its signature")
                .doesNotThrowAnyException();
    }

    @Test
    void nodeIdIsRejectedWithAToolErrorNotAnError() throws Exception {
        var loader = new NoPipelineClassLoader(getClass().getClassLoader());
        Class<?> extension = Class.forName(BUILD_LOGS, true, loader);

        // Drives resolveLogSource, the actual path from getBuildLog. Testing requirePipelineSupport()
        // in isolation would pass even if nothing called it.
        Method resolveLogSource = extension.getDeclaredMethod("resolveLogSource", hudson.model.Run.class, String.class);
        resolveLogSource.setAccessible(true);

        // Null Run is fine: the guard must fire on nodeId before anything dereferences it.
        assertThatThrownBy(() -> resolveLogSource.invoke(null, null, "7"))
                .cause()
                .as("must be a catchable Exception that McpToolWrapper renders as isError=true, "
                        + "not an Error that escapes to the transport layer")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Pipeline plugins")
                .hasMessageContaining("Omit 'nodeId'");
    }

    @Test
    void omittingNodeIdStillReachesTheWholeBuildPath() throws Exception {
        var loader = new NoPipelineClassLoader(getClass().getClassLoader());
        Class<?> extension = Class.forName(BUILD_LOGS, true, loader);
        Method resolveLogSource = extension.getDeclaredMethod("resolveLogSource", hudson.model.Run.class, String.class);
        resolveLogSource.setAccessible(true);

        // Complement of the above: without nodeId the guard must not fire, so the failure is the null
        // Run rather than a missing-plugin complaint.
        for (String noNode : new String[] {null, ""}) {
            assertThatThrownBy(() -> resolveLogSource.invoke(null, null, noNode))
                    .cause()
                    .as("nodeId=%s must take the whole-build path, not the Pipeline guard", noNode)
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void wholeBuildReadPathNeverTouchesThePipelineHelper() throws Exception {
        var loader = new NoPipelineClassLoader(getClass().getClassLoader());
        Class<?> logSource = Class.forName(LOG_SOURCE, true, loader);

        // LogSource.scopeKey(run, null) is what the whole-build path calls. Exercising it proves the
        // no-nodeId branch is reachable with Pipeline absent. (A null Run is fine: we only need the
        // method to resolve and enter, and the NPE proves we got past class resolution.)
        Method scopeKey = logSource.getMethod("scopeKey", hudson.model.Run.class, String.class);
        assertThatThrownBy(() -> scopeKey.invoke(null, null, null))
                .cause()
                .as("expected to fail on the null Run, not on a missing Pipeline class")
                .isInstanceOf(NullPointerException.class);
    }
}
