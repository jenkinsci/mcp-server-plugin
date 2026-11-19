/*
 * The MIT License
 *
 * Copyright (c) 2026, Jenkins Contributors.
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
 */

package io.jenkins.plugins.mcp.server;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import jenkins.model.Jenkins;

/**
 * Extension point for converting user inputs from MCP tool calls into Jenkins {@link ParameterValue}s.
 * <p>
 * Plugins providing custom {@link ParameterDefinition} types can implement this extension point
 * to supply custom conversion logic for MCP-triggered builds.
 */
public interface McpParameterValueProvider extends ExtensionPoint {

    /**
     * Checks if this provider is applicable for the given parameter definition.
     *
     * @param parameter the parameter definition
     * @return {@code true} if this provider can convert values for the given parameter definition, {@code false} otherwise
     */
    boolean isApplicable(@NonNull ParameterDefinition parameter);

    /**
     * Creates a {@link ParameterValue} from the given input value.
     *
     * @param parameter the parameter definition
     * @param inputValue the raw input value received from the MCP tool call
     * @return the created parameter value, or {@code null} if conversion failed
     */
    @CheckForNull
    ParameterValue createValue(@NonNull ParameterDefinition parameter, @Nullable Object inputValue);

    /**
     * Returns all registered implementations of {@link McpParameterValueProvider}.
     *
     * @return extension list of parameter value providers
     */
    static ExtensionList<McpParameterValueProvider> all() {
        return Jenkins.get().getExtensionList(McpParameterValueProvider.class);
    }
}
