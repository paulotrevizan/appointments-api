package pt.paulotrevizan.appointments.common.web;

import static org.assertj.core.api.Assertions.assertThat;

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
import pt.paulotrevizan.appointments.TestcontainersConfiguration;

@SpringBootTest(properties = "appointments.security.public-api-docs=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OpenApiPublicDocumentationTest {

  private final MockMvcTester mvc;

  OpenApiPublicDocumentationTest(@Autowired MockMvcTester mvc) {
    this.mvc = mvc;
  }

  @Test
  @DisplayName("D-026: with public API docs, the document and Swagger UI open without credentials")
  void givenPublicApiDocs_whenRequestedWithoutCredentials_thenServed() {
    assertThat(mvc.get().uri("/v3/api-docs")).hasStatusOk();
    assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatusOk();
  }

  @Test
  @DisplayName("D-026: with public API docs, the endpoints themselves still require credentials")
  void givenPublicApiDocs_whenBookingWithoutCredentials_thenUnauthorized() {
    assertThat(
            mvc.post()
                .uri("/api/v1/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .hasStatus(HttpStatus.UNAUTHORIZED);
  }
}
