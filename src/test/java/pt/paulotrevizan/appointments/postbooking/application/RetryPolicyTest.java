package pt.paulotrevizan.appointments.postbooking.application;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
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

  @ParameterizedTest(name = "SMTP {0} fails immediately")
  @ValueSource(ints = {550, 553, 554})
  @DisplayName("D-017: a permanent SMTP rejection (5xx) is not retryable")
  void givenPermanentSmtpRejection_whenClassifying_thenNotRetryable(int replyCode) {
    assertThat(RetryPolicy.isRetryable(rejectedRecipient(replyCode))).isFalse();
    assertThat(RetryPolicy.isRetryable(rejectedMessage(replyCode))).isFalse();
  }

  @ParameterizedTest(name = "SMTP {0} is retried")
  @ValueSource(ints = {421, 450, 451, 452})
  @DisplayName("D-017: a transient SMTP rejection (4xx) is retryable")
  void givenTransientSmtpRejection_whenClassifying_thenRetryable(int replyCode) {
    assertThat(RetryPolicy.isRetryable(rejectedRecipient(replyCode))).isTrue();
    assertThat(RetryPolicy.isRetryable(rejectedMessage(replyCode))).isTrue();
  }

  @Test
  @DisplayName("D-017: an unreachable or failing SMTP server is retryable")
  void givenSmtpConnectionOrAuthenticationFailure_whenClassifying_thenRetryable() {
    assertThat(
            RetryPolicy.isRetryable(
                new MailSendException(
                    "Mail server connection failed",
                    new MessagingException("Could not connect to SMTP host"))))
        .isTrue();
    assertThat(RetryPolicy.isRetryable(new MailAuthenticationException("535 invalid"))).isTrue();
  }

  @Test
  @DisplayName("D-017: an email that cannot be built is not retryable")
  void givenUnparseableMessage_whenClassifying_thenNotRetryable() {
    assertThat(RetryPolicy.isRetryable(new MailParseException("Illegal address"))).isFalse();
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

  // What JavaMail throws when RCPT TO is refused: the reply is nested in a SendFailedException.
  private static MailSendException rejectedRecipient(int replyCode) {
    try {
      InternetAddress recipient = new InternetAddress("patient@example.com");
      SendFailedException invalidAddresses =
          new SendFailedException(
              "Invalid Addresses",
              new SMTPAddressFailedException(recipient, "RCPT TO", replyCode, "rejected"));
      return new MailSendException(Map.of(new Object(), invalidAddresses));
    } catch (AddressException e) {
      throw new IllegalStateException(e);
    }
  }

  // What JavaMail throws when MAIL FROM or DATA is refused.
  private static MailSendException rejectedMessage(int replyCode) {
    return new MailSendException(
        Map.of(
            new Object(),
            new SMTPSendFailedException("DATA", replyCode, "rejected", null, null, null, null)));
  }

  private static RestClientResponseException httpError(int status) {
    HttpStatusCode code = HttpStatusCode.valueOf(status);
    return status >= 500
        ? HttpServerErrorException.create(code, "error", null, null, null)
        : HttpClientErrorException.create(code, "error", null, null, null);
  }
}
