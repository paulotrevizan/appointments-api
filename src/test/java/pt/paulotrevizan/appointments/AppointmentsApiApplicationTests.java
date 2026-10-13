package pt.paulotrevizan.appointments;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AppointmentsApiApplicationTests {

  private final MockMvcTester mvc;

  AppointmentsApiApplicationTests(@Autowired MockMvcTester mvc) {
    this.mvc = mvc;
  }

  @Test
  @DisplayName("The application starts against PostgreSQL 18 and reports healthy")
  void givenPostgresRunning_whenHealthRequestedWithoutCredentials_thenUp() {
    assertThat(mvc.get().uri("/actuator/health"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.status")
        .isEqualTo("UP");
  }

  @Test
  @DisplayName("Any other endpoint requires authentication")
  void givenNoCredentials_whenNonHealthEndpointRequested_thenUnauthorized() {
    assertThat(mvc.get().uri("/api/v1/admin/appointments")).hasStatus(HttpStatus.UNAUTHORIZED);
  }
}
