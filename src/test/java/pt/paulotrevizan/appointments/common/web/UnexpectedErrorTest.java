package pt.paulotrevizan.appointments.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pt.paulotrevizan.appointments.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, UnexpectedErrorTest.FailingController.class})
class UnexpectedErrorTest {

  private static final String INTERNAL_DETAIL = "connection string with secrets";

  private final MockMvcTester mvc;

  UnexpectedErrorTest(@Autowired MockMvcTester mvc) {
    this.mvc = mvc;
  }

  @Test
  @DisplayName("An unexpected error is a 500 problem detail that reveals no internal details")
  void givenUnexpectedException_whenHandled_thenGenericProblemDetail() {
    MvcTestResult result =
        mvc.get()
            .uri("/test/unexpected-error")
            .headers(headers -> headers.setBasicAuth("admin", "admin-test"))
            .exchange();

    assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(result).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Internal Server Error");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("An unexpected error occurred");
    assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
        .doesNotContain(INTERNAL_DETAIL);
  }

  /** Test-only endpoint: stands for any bug that escapes the mapped error cases. */
  @RestController
  static class FailingController {

    @GetMapping("/test/unexpected-error")
    String fail() {
      throw new IllegalStateException(INTERNAL_DETAIL);
    }
  }
}
