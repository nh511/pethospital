package com.pethospital.testing;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ConcurrentTestBarrier {
    private final CountDownLatch ready;
    private final CountDownLatch release = new CountDownLatch(1);

    public ConcurrentTestBarrier(int participants) {
        if (participants < 1) {
            throw new IllegalArgumentException("participants must be positive");
        }
        this.ready = new CountDownLatch(participants);
    }

    public void readyAndAwaitRelease(Duration timeout) {
        ready.countDown();
        await(release, timeout, "Timed out waiting for concurrent test release");
    }

    public void awaitAllReady(Duration timeout) {
        await(ready, timeout, "Timed out waiting for concurrent test participants");
    }

    public void release() {
        release.countDown();
    }

    private static void await(CountDownLatch latch, Duration timeout, String message) {
        try {
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError(message);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, interrupted);
        }
    }
}
