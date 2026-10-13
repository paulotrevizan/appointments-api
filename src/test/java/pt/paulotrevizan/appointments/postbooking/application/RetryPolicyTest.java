package pt.paulotrevizan.appointments.postbooking.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pt.paulotrevizan.appointments.integration.IntegrationException;

class RetryPolicyTest {

  private static final Duration CAP = Duration.ofMinutes(10);

  private final RetryPolicy policy =
      new RetryPolicy(
          new OutboxProperties(
              20,
              Duration.ofSeconds(30),
              10,
              new OutboxProperties.Backoff(
                  Duration.ofSeconds(5), 2.0, CAP, Duration.ofSeconds(1))));

  @ParameterizedTest(name = "after {0} failed attempts: {1} s ± 20 %")
  @CsvSource({"1, 5", "2, 10", "3, 20", "4, 40", "5, 80", "6, 160", "7, 320"})
  @DisplayName("D-005: the delay doubles from 5 s, with up to ±20 % jitter")
  void givenFailedAttempts_whenDelayComputed_thenExponentialWithJitter(
      int failedAttempts, long baseSeconds) {
    long base = Duration.ofSeconds(baseSeconds).toMillis();

    IntStream.range(0, 200)
        .mapToObj(i -> policy.delayAfter(failedAttempts).toMillis())
        .forEach(delay -> assertThat(delay).isBetween((long) (base * 0.8), (long) (base * 1.2)));
  }

  @Test
  @DisplayName("D-005: the delay never exceeds the 10-minute cap")
  void givenManyFailedAttempts_whenDelayComputed_thenCapped() {
    IntStream.rangeClosed(8, 20)
        .mapToObj(policy::delayAfter)
        .forEach(
            delay ->
                assertThat(delay)
                    .isLessThanOrEqualTo(CAP)
                    .isGreaterThanOrEqualTo(CAP.multipliedBy(8).dividedBy(10)));
  }

  @Test
  @DisplayName("D-017: a retryable failure is retried until the maximum number of attempts")
  void givenRetryableFailure_whenBelowMaxAttempts_thenRetried() {
    IntegrationException failure = IntegrationException.retryable("down", null);

    assertThat(policy.canRetry(failure, 1)).isTrue();
    assertThat(policy.canRetry(failure, 9)).isTrue();
    assertThat(policy.canRetry(failure, 10)).isFalse();
  }

  @Test
  @DisplayName("D-017: a permanent failure is never retried")
  void givenPermanentFailure_whenChecked_thenNotRetried() {
    assertThat(policy.canRetry(IntegrationException.permanent("rejected", null), 1)).isFalse();
  }
}
