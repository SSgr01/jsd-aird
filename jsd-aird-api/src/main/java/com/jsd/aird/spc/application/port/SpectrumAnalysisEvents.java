package com.jsd.aird.spc.application.port;
import java.util.UUID;
import java.time.Duration;
public interface SpectrumAnalysisEvents {
 Subscription subscribe(UUID runId);
 interface Subscription extends AutoCloseable {
 long version();
 void awaitChange(long observedVersion, Duration timeout) throws InterruptedException;
 void close();
 }
}
