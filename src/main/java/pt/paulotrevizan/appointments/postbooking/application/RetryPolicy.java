package pt.paulotrevizan.appointments.postbooking.application;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/** D-005, D-017: whether a failed post-booking task is tried again, and when. */
@Component
@RequiredArgsConstructor
class RetryPolicy {

  private final OutboxProperties properties;

  /** The delay before the next attempt, or empty when the task must be marked {@code FAILED}. */
  Optional<Duration> retryDelay(int attemptsMade, Exception error) {
    if (!isRetryable(error) || attemptsMade >= properties.maxAttempts()) {
      return Optional.empty();
    }
    return Optional.of(backoff(attemptsMade));
  }

  static boolean isRetryable(Exception error) {
    if (error instanceof RestClientResponseException response) {
      return response.getStatusCode().is5xxServerError()
          || response.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS);
    }
    // Timeouts, connection errors and anything unexpected: bounded by the maximum attempts.
    return true;
  }

  Duration backoff(int attemptsMade) {
    double exponential =
        properties.initialBackoff().toMillis()
            * Math.pow(properties.backoffMultiplier(), attemptsMade - 1);
    double jitter =
        1 + properties.backoffJitter() * (2 * ThreadLocalRandom.current().nextDouble() - 1);
    return Duration.ofMillis(
        (long) Math.min(exponential * jitter, properties.maxBackoff().toMillis()));
  }
}
