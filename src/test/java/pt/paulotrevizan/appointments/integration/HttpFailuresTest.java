package pt.paulotrevizan.appointments.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class HttpFailuresTest {

  @ParameterizedTest(name = "HTTP {0} → retryable: {1}")
  @CsvSource({
    "500, true",
    "502, true",
    "503, true",
    "504, true",
    "429, true",
    "400, false",
    "401, false",
    "404, false",
    "409, false",
    "422, false"
  })
  @DisplayName("D-017: 5xx and 429 are retried, any other 4xx is a permanent rejection")
  void givenHttpErrorStatus_whenClassified_thenRetryableOnlyFor5xxAnd429(
      int status, boolean retryable) {
    RestClientResponseException failure = responseError(HttpStatus.valueOf(status));

    assertThat(HttpFailures.isRetryable(failure)).isEqualTo(retryable);
    assertThat(HttpFailures.describe(failure)).isEqualTo("HTTP " + status);
  }

  @Test
  @DisplayName("D-017: timeouts and connection errors are retried")
  void givenTimeoutOrConnectionError_whenClassified_thenRetryable() {
    ResourceAccessException failure = new ResourceAccessException("Read timed out");

    assertThat(HttpFailures.isRetryable(failure)).isTrue();
    assertThat(HttpFailures.describe(failure)).isEqualTo("ResourceAccessException: Read timed out");
  }

  private static RestClientResponseException responseError(HttpStatus status) {
    return status.is5xxServerError()
        ? HttpServerErrorException.create(
            status, status.getReasonPhrase(), HttpHeaders.EMPTY, null, null)
        : HttpClientErrorException.create(
            status, status.getReasonPhrase(), HttpHeaders.EMPTY, null, null);
  }
}
