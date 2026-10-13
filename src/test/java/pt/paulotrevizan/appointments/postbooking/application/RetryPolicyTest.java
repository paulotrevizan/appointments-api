package pt.paulotrevizan.appointments.postbooking.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class RetryPolicyTest {

  private static final Duration INITIAL = Duration.ofSeconds(5);
  private static final Duration MAX = Duration.ofMinutes(10);
  private static final int MAX_ATTEMPTS = 10;

  @Test
  @DisplayName("Backoff doubles with each attempt")
  void givenNoJitter_whenAttemptsGrow_thenBackoffGrowsExponentially() {
    RetryPolicy policy = policy(0);

    assertThat(IntStream.rangeClosed(1, 5).mapToObj(policy::backoff))
        .containsExactly(
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofSeconds(20),
            Duration.ofSeconds(40),
            Duration.ofSeconds(80));
  }

  @Test
  @DisplayName("Backoff never exceeds the cap, however many attempts were made")
  void givenManyAttempts_whenComputingBackoff_thenCapped() {
    RetryPolicy policy = policy(0.2);

    assertThat(IntStream.rangeClosed(8, 1_000).mapToObj(policy::backoff))
        .allSatisfy(backoff -> assertThat(backoff).isLessThanOrEqualTo(MAX));
    assertThat(policy(0).backoff(1_000)).isEqualTo(MAX);
  }

  @Test
  @DisplayName("Jitter keeps the backoff within ±20% and spreads the values")
  void givenJitter_whenComputingBackoff_thenWithinBoundsAndSpread() {
    RetryPolicy policy = policy(0.2);

    List<Duration> backoffs = IntStream.range(0, 1_000).mapToObj(i -> policy.backoff(2)).toList();

    assertThat(backoffs)
        .allSatisfy(
            backoff ->
                assertThat(backoff).isBetween(Duration.ofSeconds(8), Duration.ofSeconds(12)));
    assertThat(backoffs.stream().distinct().count()).isGreaterThan(100);
  }

  @ParameterizedTest(name = "HTTP {0} is retried")
  @ValueSource(ints = {429, 500, 502, 503, 504})
  @DisplayName("D-017: HTTP 5xx and 429 are retryable")
  void givenServerErrorOrTooManyRequests_whenClassifying_thenRetryable(int status) {
    assertThat(RetryPolicy.isRetryable(httpError(status))).isTrue();
  }

  @ParameterizedTest(name = "HTTP {0} fails immediately")
  @ValueSource(ints = {400, 401, 403, 404, 409, 422})
  @DisplayName("D-017: any other HTTP 4xx is not retryable")
  void givenClientError_whenClassifying_thenNotRetryable(int status) {
    assertThat(RetryPolicy.isRetryable(httpError(status))).isFalse();
  }

  @Test
  @DisplayName("D-017: timeouts and connection errors are retryable")
  void givenTimeoutOrConnectionError_whenClassifying_thenRetryable() {
    assertThat(
            RetryPolicy.isRetryable(
                new ResourceAccessException("timeout", new HttpTimeoutException("timed out"))))
        .isTrue();
    assertThat(
            RetryPolicy.isRetryable(
                new ResourceAccessException("refused", new IOException("Connection refused"))))
        .isTrue();
  }

  @Test
  @DisplayName("A retryable failure gets a delay until the maximum attempts are used")
  void givenRetryableFailure_whenAttemptsRemain_thenDelayed() {
    RetryPolicy policy = policy(0);
    Exception unavailable = httpError(503);

    assertThat(policy.retryDelay(1, unavailable)).contains(INITIAL);
    assertThat(policy.retryDelay(MAX_ATTEMPTS - 1, unavailable)).isPresent();
    assertThat(policy.retryDelay(MAX_ATTEMPTS, unavailable)).isEmpty();
  }

  @Test
  @DisplayName("D-017: a non-retryable failure gives up on the first attempt")
  void givenNonRetryableFailure_whenFirstAttempt_thenGivesUp() {
    assertThat(policy(0).retryDelay(1, httpError(409))).isEmpty();
  }

  private static RetryPolicy policy(double jitter) {
    return new RetryPolicy(
        new OutboxProperties(20, Duration.ofSeconds(30), MAX_ATTEMPTS, INITIAL, MAX, 2, jitter));
  }

  private static RestClientResponseException httpError(int status) {
    HttpStatusCode code = HttpStatusCode.valueOf(status);
    return status >= 500
        ? HttpServerErrorException.create(code, "error", null, null, null)
        : HttpClientErrorException.create(code, "error", null, null, null);
  }
}
