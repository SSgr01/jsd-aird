package com.jsd.aird.shared.resource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeadlineInterruptTest {
    @Test
    void blockedInputCloseDoesNotHoldDeadlineMonitorOrDelayOtherDeadlines() throws Exception {
        var closing = new CountDownLatch(1);
        var releaseClose = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var owner = Thread.ofVirtual().start(() -> {
            try (var deadline = new DeadlineInterrupt(30, () -> {
                closing.countDown();
                try { releaseClose.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            })) {
                try { Thread.sleep(10_000); }
                catch (InterruptedException expected) {
                    if (deadline.expired()) finished.countDown();
                }
            }
        });
        try {
            assertTrue(closing.await(2, TimeUnit.SECONDS), "input cancellation should start");
            assertTrue(finished.await(2, TimeUnit.SECONDS), "reader must inspect expiry while close is blocked");
            var secondFinished = new CountDownLatch(1);
            var second = Thread.ofVirtual().start(() -> {
                try (var deadline = new DeadlineInterrupt(30)) {
                    try { Thread.sleep(10_000); }
                    catch (InterruptedException expected) {
                        if (deadline.expired()) secondFinished.countDown();
                    }
                }
            });
            assertTrue(secondFinished.await(2, TimeUnit.SECONDS), "one blocked close must not delay another upload");
            second.join(2_000);
        } finally {
            releaseClose.countDown();
            owner.interrupt();
            owner.join(2_000);
        }
    }
}
