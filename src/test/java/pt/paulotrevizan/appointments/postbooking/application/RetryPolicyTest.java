package pt.paulotrevizan.appointments.postbooking.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RetryPolicyTest {

  private static final Instant NOW = Instant.parse("2030-01-07T10:00:00Z");
  private static final Duration MAX = Duration.ofMinutes(10);

  private final RetryPolicy policy =
      new RetryPolicy(new OutboxProperties(10, 10, Duration.ofSeconds(5), MAX, 0.2));

  @ParameterizedTest(name = "after {0} failed attempts: {1} s ± 20 %")
  @CsvSource({"1, 5", "2, 10", "3, 20", "4, 40", "5, 80", "6, 160", "7, 320"})
  @DisplayName("D-005: the delay doubles from 5 s, with up to ±20 % jitter")
  void givenFailedAttempts_whenNextAttemptComputed_thenExponentialWithJitter(
      int failedAttempts, long baseSeconds) {
    long base = Duration.ofSeconds(baseSeconds).toMillis();

    IntStream.range(0, 200)
        .forEach(
            i ->
                assertThat(delayAfter(failedAttempts))
                    .isBetween(Math.round(base * 0.8), Math.round(base * 1.2)));
  }

  @Test
  @DisplayName("D-005: the delay never exceeds the 10-minute cap (± jitter)")
  void givenManyFailedAttempts_whenNextAttemptComputed_thenCapped() {
    IntStream.rangeClosed(8, 9)
        .forEach(
            attempts ->
                assertThat(delayAfter(attempts))
                    .isBetween(Math.round(MAX.toMillis() * 0.8), Math.round(MAX.toMillis() * 1.2)));
  }

  @Test
  @DisplayName("D-005: there is no next attempt once the maximum number of attempts is reached")
  void givenMaxAttemptsReached_whenNextAttemptComputed_thenNone() {
    assertThat(policy.nextAttemptAt(9, NOW)).isNotNull();
    assertThat(policy.nextAttemptAt(10, NOW)).isNull();
  }

  private long delayAfter(int failedAttempts) {
    return Duration.between(NOW, policy.nextAttemptAt(failedAttempts, NOW)).toMillis();
  }
}
