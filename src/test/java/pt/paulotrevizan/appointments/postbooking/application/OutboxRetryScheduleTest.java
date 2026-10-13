package pt.paulotrevizan.appointments.postbooking.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pt.paulotrevizan.appointments.integration.IntegrationException;

class OutboxRetryScheduleTest {

  private static final Duration CAP = Duration.ofMinutes(10);
  private static final IntegrationException TRANSIENT =
      IntegrationException.retryable("HTTP 503", null);

  private final OutboxRetrySchedule schedule =
      new OutboxRetrySchedule(
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
        .forEach(
            i ->
                assertThat(schedule.delayAfter(TRANSIENT, failedAttempts))
                    .get()
                    .extracting(Duration::toMillis)
                    .satisfies(
                        delay ->
                            assertThat(delay).isBetween((long) (base * 0.8), (long) (base * 1.2))));
  }

  @Test
  @DisplayName("D-005: the delay never exceeds the 10-minute cap")
  void givenManyFailedAttempts_whenDelayComputed_thenCapped() {
    IntStream.rangeClosed(8, 9)
        .forEach(
            attempts ->
                assertThat(schedule.delayAfter(TRANSIENT, attempts))
                    .get()
                    .satisfies(
                        delay ->
                            assertThat(delay)
                                .isLessThanOrEqualTo(CAP)
                                .isGreaterThanOrEqualTo(CAP.multipliedBy(8).dividedBy(10))));
  }

  @Test
  @DisplayName("D-005: a retryable failure gets no further attempt once the maximum is reached")
  void givenRetryableFailure_whenMaxAttemptsReached_thenNoDelay() {
    assertThat(schedule.delayAfter(TRANSIENT, 9)).isPresent();
    assertThat(schedule.delayAfter(TRANSIENT, 10)).isEmpty();
  }

  @Test
  @DisplayName("D-017: a permanent failure is never retried")
  void givenPermanentFailure_whenScheduled_thenNoDelay() {
    assertThat(schedule.delayAfter(IntegrationException.permanent("HTTP 422", null), 1)).isEmpty();
  }
}
