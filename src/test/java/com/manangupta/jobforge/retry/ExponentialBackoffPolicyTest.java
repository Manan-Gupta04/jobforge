package com.manangupta.jobforge.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExponentialBackoffPolicyTest {

    @Test
    void doublesEachAttemptWithoutJitter() {
        ExponentialBackoffPolicy p = new ExponentialBackoffPolicy(100, 100_000, 0.0);
        assertThat(p.delayFor(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(p.delayFor(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(p.delayFor(3)).isEqualTo(Duration.ofMillis(400));
        assertThat(p.delayFor(5)).isEqualTo(Duration.ofMillis(1600));
    }

    @Test
    void capsAtMaximum() {
        ExponentialBackoffPolicy p = new ExponentialBackoffPolicy(1000, 5000, 0.0);
        assertThat(p.delayFor(10)).isEqualTo(Duration.ofMillis(5000));
        assertThat(p.delayFor(100)).isEqualTo(Duration.ofMillis(5000)); // no overflow for huge attempts
    }

    @Test
    void jitterStaysWithinBounds() {
        ExponentialBackoffPolicy p = new ExponentialBackoffPolicy(1000, 60_000, 0.2);
        for (int i = 0; i < 500; i++) {
            long ms = p.delayFor(3).toMillis(); // raw 4000
            assertThat(ms).isBetween(3200L, 4800L);
        }
    }

    @Test
    void neverBelowOneMillisecond() {
        ExponentialBackoffPolicy p = new ExponentialBackoffPolicy(1, 10, 0.9);
        for (int i = 0; i < 200; i++) {
            assertThat(p.delayFor(1).toMillis()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void rejectsBadConfiguration() {
        assertThatThrownBy(() -> new ExponentialBackoffPolicy(0, 10, 0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExponentialBackoffPolicy(10, 10, 1.0)).isInstanceOf(IllegalArgumentException.class);
    }
}
