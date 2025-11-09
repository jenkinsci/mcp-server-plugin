package io.jenkins.plugins.mcp.server.tool;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;

@Data
public class JenkinsMcpContext {
    private static final ThreadLocal<JenkinsMcpContext> CONTEXT = ThreadLocal.withInitial(JenkinsMcpContext::new);

    HttpServletRequest httpServletRequest;

    /**
     * Gets the JenkinsMcpContext for the current thread. Creates a new one if it doesn't exist.
     *
     * @return The thread-local JenkinsMcpContext instance.
     */
    public static JenkinsMcpContext get() {
        return CONTEXT.get();
    }

    /**
     * Clears the JenkinsMcpContext for the current thread to prevent memory leaks.
     */
    public static void clear() {
        CONTEXT.remove();
    }
}
