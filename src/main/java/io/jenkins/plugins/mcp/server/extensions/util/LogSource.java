package io.jenkins.plugins.mcp.server.extensions.util;

import hudson.console.AnnotatedLargeText;
import hudson.model.Run;
import java.util.function.BooleanSupplier;

/**
 * What log to read and how to scope a resume cursor to it, so the paginated read helpers serve both a
 * whole-build log and a single Pipeline node's log without duplicating the window arithmetic.
 *
 * @param text raw log bytes (console notes included, so byte offsets are cursor-stable)
 * @param liveness whether more output may still be appended; see {@link #live()}
 * @param scopeKey identity a cursor is bound to; must distinguish job, build, and node
 */
public record LogSource(AnnotatedLargeText<?> text, BooleanSupplier liveness, String scopeKey) {

    /**
     * Whether more output may still be appended, answered <em>now</em> rather than when this record was
     * built. A read of a large log takes long enough for the build or node to finish while it is in
     * flight, and a stale {@code true} hands back a resume cursor that can never yield anything.
     */
    public boolean live() {
        return liveness.getAsBoolean();
    }

    public static LogSource ofRun(Run<?, ?> run) {
        return new LogSource(run.getLogText(), run::isLogUpdated, scopeKey(run, null));
    }

    /**
     * The job's full name is part of the key because without it a cursor issued for one job is honoured
     * against another, handing back a byte offset into an unrelated log.
     */
    public static String scopeKey(Run<?, ?> run, String nodeId) {
        String base = run.getParent().getFullName() + "#" + run.getNumber();
        return nodeId == null ? base : base + "/" + nodeId;
    }
}
