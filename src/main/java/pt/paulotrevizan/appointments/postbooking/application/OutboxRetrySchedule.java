package pt.paulotrevizan.appointments.postbooking.application;

import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.BackOffExecution;
import pt.paulotrevizan.appointments.integration.IntegrationException;

/**
 * D-005 and D-017 as a Spring {@link RetryPolicy}: which failures are retried, how many times and
 * with which backoff. The outbox persists the next attempt instead of retrying in memory, so the
 * backoff sequence is replayed up to the number of failed attempts to get the next delay.
 */
@Component
@EnableConfigurationProperties(OutboxProperties.class)
class OutboxRetrySchedule {

  private final RetryPolicy policy;

  OutboxRetrySchedule(OutboxProperties properties) {
    OutboxProperties.Backoff backoff = properties.backoff();
    this.policy =
        RetryPolicy.builder()
            .predicate(
                error -> error instanceof IntegrationException failure && failure.isRetryable())
            .maxRetries(properties.maxAttempts() - 1)
            .delay(backoff.initial())
            .multiplier(backoff.multiplier())
            .maxDelay(backoff.max())
            .jitter(backoff.jitter())
            .build();
  }

  /** The delay before the next attempt, or empty when the task must be marked FAILED. */
  Optional<Duration> delayAfter(IntegrationException failure, int failedAttempts) {
    if (!policy.shouldRetry(failure)) {
      return Optional.empty();
    }
    BackOffExecution backOff = policy.getBackOff().start();
    long delay = BackOffExecution.STOP;
    for (int attempt = 1; attempt <= failedAttempts; attempt++) {
      delay = backOff.nextBackOff();
    }
    return delay == BackOffExecution.STOP
        ? Optional.empty()
        : Optional.of(Duration.ofMillis(delay));
  }
}
