package pt.paulotrevizan.appointments.postbooking.application;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * D-005: exponential backoff for the outbox. The attempt count is persisted with the task, so the
 * delay is a function of it: {@code min(initialDelay × 2^(attempts-1), maxDelay)} ± jitter.
 */
@Component
@EnableConfigurationProperties(OutboxProperties.class)
@RequiredArgsConstructor
class RetryPolicy {

  private final OutboxProperties properties;

  /** When to retry after {@code failedAttempts} failures, or {@code null} once they are used up. */
  Instant nextAttemptAt(int failedAttempts, Instant now) {
    if (failedAttempts >= properties.maxAttempts()) {
      return null;
    }
    long delay =
        Math.min(
            properties.initialDelay().toMillis() << Math.min(failedAttempts - 1, 30),
            properties.maxDelay().toMillis());
    double jitter = properties.jitter() * (2 * ThreadLocalRandom.current().nextDouble() - 1);
    return now.plusMillis(Math.round(delay * (1 + jitter)));
  }
}
