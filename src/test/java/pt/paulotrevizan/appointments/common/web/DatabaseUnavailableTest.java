package pt.paulotrevizan.appointments.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pt.paulotrevizan.appointments.TestcontainersConfiguration;

/**
 * Stops this context's PostgreSQL; the context is discarded afterwards so no other test sees it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DirtiesContext
class DatabaseUnavailableTest {

  // Hikari's connection-timeout is 3 s; the margin covers connection validation and CI noise.
  private static final Duration ANSWERED_WITHIN = Duration.ofSeconds(10);

  private final MockMvcTester mvc;
  private final PostgreSQLContainer postgres;

  DatabaseUnavailableTest(@Autowired MockMvcTester mvc, @Autowired PostgreSQLContainer postgres) {
    this.mvc = mvc;
    this.postgres = postgres;
  }

  @BeforeEach
  void stopDatabase() {
    postgres.stop();
  }

  @Test
  @DisplayName("HLD 12: with PostgreSQL down, booking answers 503 problem detail within seconds")
  void givenDatabaseDown_whenBooking_thenServiceUnavailableQuickly() {
    long started = System.nanoTime();

    MvcTestResult result =
        mvc.post()
            .uri("/api/v1/appointments")
            .headers(headers -> headers.setBasicAuth("booking-client", "booking-test"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"patientId": "00000000-0000-0000-0000-000000000001",
                 "specialty": "CARDIOLOGY", "startAt": "2035-03-05T14:00:00Z"}
                """)
            .exchange();

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(ANSWERED_WITHIN);
    assertServiceUnavailable(result, "/api/v1/appointments");
  }

  @Test
  @DisplayName("HLD 12: with PostgreSQL down, the admin listing answers 503 problem detail")
  void givenDatabaseDown_whenListing_thenServiceUnavailable() {
    MvcTestResult result =
        mvc.get()
            .uri("/api/v1/admin/appointments")
            .headers(headers -> headers.setBasicAuth("admin", "admin-test"))
            .exchange();

    assertServiceUnavailable(result, "/api/v1/admin/appointments");
  }

  @Test
  @DisplayName("HLD 12: with PostgreSQL down, readiness fails so traffic stops")
  void givenDatabaseDown_whenProbingReadiness_thenDown() {
    MvcTestResult result = mvc.get().uri("/actuator/health/readiness").exchange();

    assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("DOWN");
  }

  private static void assertServiceUnavailable(MvcTestResult result, String instance) {
    assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(result).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Service Unavailable");
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(503);
    assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo(instance);
    assertThat(result).bodyJson().doesNotHavePath("$.code");
  }
}
