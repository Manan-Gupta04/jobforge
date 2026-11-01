package com.manangupta.jobforge.service;

import com.manangupta.jobforge.domain.Job;

/** Result of a submission; {@code created} is false when an idempotency key matched an existing job. */
public record SubmitResult(Job job, boolean created) {
}
