package com.jsd.aird.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModelCircuitBreakerTest {

    @Test
    void opensAfterThresholdAndClosesAfterSuccessfulProbe() throws InterruptedException {
        var breaker = new ModelCircuitBreaker(2, 1);

        assertThat(breaker.allow("chat")).isTrue();
        breaker.failure("chat");
        assertThat(breaker.allow("chat")).isTrue();
        breaker.failure("chat");
        assertThat(breaker.status("chat")).isEqualTo("OPEN");
        assertThat(breaker.allow("chat")).isFalse();

        Thread.sleep(1_100L);
        assertThat(breaker.allow("chat")).isTrue();
        breaker.success("chat");
        assertThat(breaker.status("chat")).isEqualTo("CLOSED");
    }
}
