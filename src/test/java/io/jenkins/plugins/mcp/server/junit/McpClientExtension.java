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

package io.jenkins.plugins.mcp.server.junit;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.jvnet.hudson.test.JenkinsRule;

@Order(Integer.MAX_VALUE)
public class McpClientExtension implements TestTemplateInvocationContextProvider {

    @Override
    public boolean supportsTestTemplate(ExtensionContext context) {
        Optional<Method> testMethod = context.getTestMethod();
        if (testMethod.isEmpty()) {
            return false;
        }

        // 检查方法参数
        Class<?>[] parameterTypes = testMethod.get().getParameterTypes();
        boolean hasJenkins = Arrays.asList(parameterTypes).contains(JenkinsRule.class);
        boolean hasMcpClientProvider = Arrays.asList(parameterTypes).contains(JenkinsMcpClientBuilder.class);

        return hasJenkins && hasMcpClientProvider;
    }

    // 提供多次调用的上下文，每个上下文代表一次执行
    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {

        return Stream.<JenkinsMcpClientBuilder>of(
                        new JenkinsSSEMcpClientBuilder(), new JenkinsStreamableMcpClientBuilder())
                .map(this::invocationContext);
    }

    // 构建单次执行的上下文，内部包含 ParameterResolver
    private TestTemplateInvocationContext invocationContext(JenkinsMcpClientBuilder builder) {
        return new TestTemplateInvocationContext() {
            @Override
            public String getDisplayName(int index) {
                return builder.toString();
            }

            @Override
            public List<Extension> getAdditionalExtensions() {
                return List.of(new ParameterResolver() {
                    @Override
                    public boolean supportsParameter(ParameterContext pc, ExtensionContext ec) {
                        return pc.getParameter().getType().equals(JenkinsMcpClientBuilder.class);
                    }

                    @Override
                    public Object resolveParameter(ParameterContext pc, ExtensionContext ec) {
                        return builder;
                    }
                });
            }
        };
    }
}
