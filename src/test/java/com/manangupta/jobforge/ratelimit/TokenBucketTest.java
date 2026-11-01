package com.manangupta.jobforge.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void startsFullAndDrainsToZero() {
        TokenBucket b = new TokenBucket(5, 5, 0);
        long t = 0;
        for (int i = 0; i < 5; i++) {
            assertThat(b.tryAcquireAt(t)).isTrue();
        }
        assertThat(b.tryAcquireAt(t)).isFalse();
    }

    @Test
    void refillsAtTheConfiguredRate() {
        TokenBucket b = new TokenBucket(2, 2, 0); // 2 per second
        long t = 0;
        assertThat(b.tryAcquireAt(t)).isTrue();
        assertThat(b.tryAcquireAt(t)).isTrue();
        assertThat(b.tryAcquireAt(t)).isFalse();
        assertThat(b.tryAcquireAt(t + SECOND / 2)).isTrue();   // 0.5 s -> 1 token
        assertThat(b.tryAcquireAt(t + SECOND / 2)).isFalse();
        assertThat(b.tryAcquireAt(t + SECOND)).isTrue();       // another 0.5 s -> 1 more
    }

    @Test
    void neverExceedsCapacityAfterIdling() {
        TokenBucket b = new TokenBucket(10, 3, 0);
        long t = 60 * SECOND;
        assertThat(b.tryAcquireAt(t)).isTrue();
        assertThat(b.tryAcquireAt(t)).isTrue();
        assertThat(b.tryAcquireAt(t)).isTrue();
        assertThat(b.tryAcquireAt(t)).isFalse();
    }

    @Test
    void rejectsNonPositiveConfiguration() {
        assertThatThrownBy(() -> new TokenBucket(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
