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

package io.jenkins.plugins.mcp.server.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Tool {

    /**
     * The name of the tool. If not provided, the method name will be used.
     */
    String name() default "";

    /**
     * The description of the tool. If not provided, the method name will be used.
     */
    String description() default "";

    /**
     * To add some _meta content to the tool.
     */
    Meta[] metas() default @Meta;

    /**
     * The _meta property/parameter is reserved by MCP to allow clients and servers
     * to attach additional metadata to their interactions.
     * <a href="https://modelcontextprotocol.io/specification/2025-06-18/basic/index#meta">specifications available</a>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.ANNOTATION_TYPE)
    @interface Meta {

        String property() default "";

        String parameter() default "";
    }

    /**
     * Additional hints for clients.
     * There is no default value for this. If you need it you need to explicitly add it.
     */
    Annotations annotations() default @Annotations;

    /**
     * <a href="https://modelcontextprotocol.io/specification/2025-06-18/schema#toolannotations">specifications available</a>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.ANNOTATION_TYPE)
    @interface Annotations {

        /**
         * A human-readable title for the tool.
         */
        String title() default "";

        /**
         * If true, the tool does not modify its environment.
         */
        boolean readOnlyHint() default false;

        /**
         * If true, the tool may perform destructive updates to its environment. If false, the tool performs only additive
         * updates.
         */
        boolean destructiveHint() default true;

        /**
         * If true, calling the tool repeatedly with the same arguments will have no additional effect on the its environment.
         */
        boolean idempotentHint() default false;

        /**
         * If true, this tool may interact with an "open world" of external entities. If false, the tool's domain of interaction
         * is closed.
         */
        boolean openWorldHint() default true;

        /**
         * hint indicating that the tool's result should be returned directly to the client,
         * rather than being processed or modified by the server
         */
        boolean returnDirect() default true;
    }
}
