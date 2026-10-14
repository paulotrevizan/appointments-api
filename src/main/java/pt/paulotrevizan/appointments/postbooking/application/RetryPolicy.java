package pt.paulotrevizan.appointments.postbooking.application;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
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
    return switch (error) {
      case RestClientResponseException response ->
          response.getStatusCode().is5xxServerError()
              || response.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS);
      case MailSendException send -> !isPermanentSmtpRejection(send);
      // A message that cannot be built (e.g. an invalid address) will never be sent.
      case MailParseException _, MailPreparationException _ -> false;
      // Timeouts, connection errors and anything unexpected: bounded by the maximum attempts.
      default -> true;
    };
  }

  // SMTP reply codes: 4xx is transient, 5xx is permanent (RFC 5321 §4.2.1).
  private static boolean isPermanentSmtpRejection(MailSendException error) {
    return Stream.concat(Stream.of(error), Arrays.stream(error.getMessageExceptions()))
        .flatMap(cause -> Stream.iterate(cause, Objects::nonNull, Throwable::getCause))
        .anyMatch(cause -> smtpReplyCode(cause) >= 500);
  }

  private static int smtpReplyCode(Throwable error) {
    return switch (error) {
      case SMTPAddressFailedException rejected -> rejected.getReturnCode();
      case SMTPSendFailedException rejected -> rejected.getReturnCode();
      default -> -1;
    };
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
