package io.jenkins.plugins.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The SSE message pool keeps hung {@code Mono.block()} calls off the Jetty threads, so its cap has to be
 * reachable and every cap value has to build. Both are properties of the pool alone, so this needs no
 * running controller.
 */
class SseMessageExecutorTest {

    private final int previousMaxThreads = Endpoint.SSE_MESSAGE_MAX_THREADS;

    @AfterEach
    void restoreMaxThreads() {
        Endpoint.SSE_MESSAGE_MAX_THREADS = previousMaxThreads;
    }

    @Test
    void capBelowFourStillBuildsAPool() {
        Endpoint.SSE_MESSAGE_MAX_THREADS = 1;

        ExecutorService executor = Endpoint.createSseMessageExecutor();
        try {
            assertThat(((ThreadPoolExecutor) executor).getMaximumPoolSize()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void zeroCapStillBuildsAWorkingPool() throws Exception {
        assertCapFallsBackToOneThread(0);
    }

    @Test
    void negativeCapStillBuildsAWorkingPool() throws Exception {
        assertCapFallsBackToOneThread(-3);
    }

    /**
     * A cap of zero or less is what an operator gets by mistyping the system property. Without the
     * minimum the constructor rejects the value, and a pool that did build with no worker would accept
     * the task and never run it, so the fallback has to be exercised end to end.
     */
    private static void assertCapFallsBackToOneThread(int cap) throws Exception {
        Endpoint.SSE_MESSAGE_MAX_THREADS = cap;

        ExecutorService executor = Endpoint.createSseMessageExecutor();
        CountDownLatch ran = new CountDownLatch(1);
        try {
            assertThat(((ThreadPoolExecutor) executor).getMaximumPoolSize()).isEqualTo(1);
            executor.execute(ran::countDown);
            assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void everyConfiguredThreadIsUsedForBlockedMessages() throws Exception {
        Endpoint.SSE_MESSAGE_MAX_THREADS = 6;

        ExecutorService executor = Endpoint.createSseMessageExecutor();
        CountDownLatch allStarted = new CountDownLatch(6);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 6; i++) {
                executor.execute(() -> {
                    allStarted.countDown();
                    await(release);
                });
            }
            // A blocked handler holds its thread, so the extra messages wait in the queue. Only a pool
            // that really grows to the cap starts them all.
            assertThat(allStarted.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
