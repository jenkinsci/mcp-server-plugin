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

import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import java.util.Arrays;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import org.junit.jupiter.params.provider.Arguments;
import org.jvnet.hudson.test.JenkinsRule;

public class TestUtils {
    public static Stream<Arguments> appendMcpClientArgs(Stream<Arguments> baseArgs) {
        return baseArgs.flatMap(args -> Stream.of(
                Arguments.of(append(args.get(), new JenkinsSSEMcpClientBuilder())),
                Arguments.of(append(args.get(), new JenkinsStreamableMcpClientBuilder()))));
    }

    private static Object[] append(Object[] original, Object extra) {
        Object[] combined = Arrays.copyOf(original, original.length + 1);
        combined[original.length] = extra;
        return combined;
    }

    @SneakyThrows
    public static void enableSecurity(JenkinsRule jenkins) {
        JenkinsRule.DummySecurityRealm securityRealm = jenkins.createDummySecurityRealm();
        jenkins.jenkins.setSecurityRealm(securityRealm);
        var authStrategy = new FullControlOnceLoggedInAuthorizationStrategy();
        authStrategy.setAllowAnonymousRead(false);
        jenkins.jenkins.setAuthorizationStrategy(authStrategy);
        jenkins.jenkins.save();
    }
}
