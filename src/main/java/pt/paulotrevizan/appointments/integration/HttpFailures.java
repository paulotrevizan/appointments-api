package pt.paulotrevizan.appointments.integration;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;

/**
 * D-017: only an answer the system will repeat — a 4xx other than 429 — is permanent. Timeouts,
 * connection errors, 5xx and 429 may succeed later.
 */
public final class HttpFailures {

  private HttpFailures() {}

  public static boolean isRetryable(Throwable failure) {
    if (failure instanceof RestClientResponseException response) {
      HttpStatusCode status = response.getStatusCode();
      return status.is5xxServerError() || status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS);
    }
    return true;
  }

  /** A short reason for {@code last_error}: the status of an HTTP answer, otherwise the error. */
  public static String describe(Throwable failure) {
    if (failure instanceof RestClientResponseException response) {
      return "HTTP " + response.getStatusCode().value();
    }
    return failure.getClass().getSimpleName() + ": " + failure.getMessage();
  }
}
