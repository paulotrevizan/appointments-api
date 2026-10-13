package pt.paulotrevizan.appointments.postbooking.application;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;
import pt.paulotrevizan.appointments.integration.IntegrationException;

/**
 * D-005 and D-017. The delay is persisted as {@code next_attempt_at}, so it is computed here and
 * not by an in-memory retry; Spring's {@link ExponentialBackOff} supplies the curve (its jitter
 * grows with the interval, which gives the ±20 % of the plan).
 */
@Component
@EnableConfigurationProperties(OutboxProperties.class)
@RequiredArgsConstructor
class RetryPolicy {

  private final OutboxProperties properties;

  boolean canRetry(IntegrationException failure, int failedAttempts) {
    return failure.isRetryable() && failedAttempts < properties.maxAttempts();
  }

  Duration delayAfter(int failedAttempts) {
    OutboxProperties.Backoff settings = properties.backoff();
    ExponentialBackOff backOff =
        new ExponentialBackOff(settings.initial().toMillis(), settings.multiplier());
    backOff.setMaxInterval(settings.max().toMillis());
    backOff.setJitter(settings.jitter().toMillis());
    BackOffExecution execution = backOff.start();
    long delay = 0;
    for (int attempt = 1; attempt <= failedAttempts; attempt++) {
      delay = execution.nextBackOff();
    }
    return Duration.ofMillis(delay);
  }
}
