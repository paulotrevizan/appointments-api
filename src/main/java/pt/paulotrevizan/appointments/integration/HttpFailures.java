package pt.paulotrevizan.appointments.integration;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * D-017: timeouts, connection errors, 5xx and 429 are retryable; any other 4xx is a rejection that
 * retrying cannot fix.
 */
public final class HttpFailures {

  private HttpFailures() {}

  public static IntegrationException classify(String system, RestClientException failure) {
    if (failure instanceof RestClientResponseException response) {
      HttpStatusCode status = response.getStatusCode();
      String message = system + " answered HTTP " + status.value();
      return status.is5xxServerError() || status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)
          ? IntegrationException.retryable(message, failure)
          : IntegrationException.permanent(message, failure);
    }
    return IntegrationException.retryable(
        system + " unreachable: " + failure.getMessage(), failure);
  }
}
