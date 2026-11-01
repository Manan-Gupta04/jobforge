package com.manangupta.jobforge.retry;

import java.time.Duration;

/** Decides how long to wait before the next attempt of a failed job. */
public interface BackoffPolicy {

    /** @param attempt the 1-based number of the attempt that just failed */
    Duration delayFor(int attempt);
}
