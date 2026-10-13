package pt.paulotrevizan.appointments.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import pt.paulotrevizan.appointments.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OpenApiDocumentationTest {

  private final MockMvcTester mvc;

  OpenApiDocumentationTest(@Autowired MockMvcTester mvc) {
    this.mvc = mvc;
  }

  @Test
  @DisplayName("D-026: the API document requires authentication")
  void givenNoCredentials_whenApiDocsRequested_thenUnauthorized() {
    assertThat(mvc.get().uri("/v3/api-docs")).hasStatus(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @DisplayName(
      "D-026: any authenticated caller can read the API document, which describes the booking endpoint")
  void givenCredentials_whenApiDocsRequested_thenDocumentDescribesBooking() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBasicAuth("booking-client", "booking-test");

    assertThat(mvc.get().uri("/v3/api-docs").headers(headers))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.paths['/api/v1/appointments'].post.operationId")
        .isEqualTo("book");
  }

  @Test
  @DisplayName("D-026: Swagger UI is served to authenticated callers")
  void givenCredentials_whenSwaggerUiRequested_thenServed() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBasicAuth("admin", "admin-test");

    assertThat(mvc.get().uri("/swagger-ui/index.html").headers(headers)).hasStatusOk();
  }
}
