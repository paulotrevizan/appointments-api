package pt.paulotrevizan.appointments;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import pt.paulotrevizan.appointments.booking.BookingFixtures;
import pt.paulotrevizan.appointments.catalog.domain.Patient;
import pt.paulotrevizan.appointments.catalog.domain.Specialty;
import pt.paulotrevizan.appointments.catalog.persistence.DoctorRepository;
import pt.paulotrevizan.appointments.catalog.persistence.PatientRepository;
import pt.paulotrevizan.appointments.catalog.persistence.RoomRepository;
import pt.paulotrevizan.appointments.catalog.persistence.SpecialtyRepository;

/** HLD 13: health probes and the metrics an operator alerts on. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ObservabilityTest {

  private static final String BOOKINGS = "appointments.bookings";
  private static final String CONFLICTS = "appointments.booking.conflicts";
  private static final String OLDEST_PENDING_AGE = "appointments.outbox.oldest.pending.age";
  private static final String FAILED = "appointments.outbox.failed";

  private final MockMvcTester mvc;
  private final JdbcClient jdbc;
  private final BookingFixtures fixtures;

  ObservabilityTest(
      @Autowired MockMvcTester mvc,
      @Autowired JdbcClient jdbc,
      @Autowired Clock clock,
      @Autowired PatientRepository patients,
      @Autowired SpecialtyRepository specialties,
      @Autowired DoctorRepository doctors,
      @Autowired RoomRepository rooms) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.fixtures = new BookingFixtures(patients, specialties, doctors, rooms, clock);
  }

  @BeforeEach
  void emptyDatabase() {
    jdbc.sql("TRUNCATE outbox_task, appointment, doctor, room, patient, specialty CASCADE")
        .update();
  }

  @Test
  @DisplayName("HLD 13: the operational metrics are exposed under their expected names")
  void givenBooking_whenListingMetrics_thenExpectedNamesExposed() {
    book(fixtures.patient().getId(), newSpecialtyWithDoctorAndRoom());

    MvcTestResult result = mvc.get().uri("/actuator/metrics").headers(admin()).exchange();

    assertThat(result).hasStatusOk();
    assertThat(result)
        .bodyJson()
        .extractingPath("$.names")
        .asArray()
        .contains(BOOKINGS, CONFLICTS, OLDEST_PENDING_AGE, FAILED, "http.server.requests");
    assertThat(availableTagValues(CONFLICTS, "resource"))
        .containsExactlyInAnyOrder("doctor", "room", "patient");
    assertThat(availableTagValues(FAILED, "type"))
        .containsExactlyInAnyOrder("DOCTOR_CALENDAR", "ROOM_RESERVATION", "EMAIL");
  }

  @Test
  @DisplayName("HLD 13: booking requests are timed and counted by outcome, repeats included")
  void givenBookedRepeatedAndRejectedRequests_whenReadingMetrics_thenCountedByOutcome() {
    double bookedBefore = count(BOOKINGS, "outcome:BOOKED");
    double repeatedBefore = count(BOOKINGS, "outcome:REPEATED");
    double notFoundBefore = count(BOOKINGS, "outcome:PATIENT_NOT_FOUND");
    Specialty specialty = newSpecialtyWithDoctorAndRoom();
    Patient patient = fixtures.patient();

    book(patient.getId(), specialty);
    book(patient.getId(), specialty);
    book(UUID.randomUUID(), specialty);

    assertThat(count(BOOKINGS, "outcome:BOOKED")).isEqualTo(bookedBefore + 1);
    assertThat(count(BOOKINGS, "outcome:REPEATED")).isEqualTo(repeatedBefore + 1);
    assertThat(count(BOOKINGS, "outcome:PATIENT_NOT_FOUND")).isEqualTo(notFoundBefore + 1);
    assertThat(measurement(BOOKINGS, "outcome:BOOKED", "TOTAL_TIME")).isPositive();
  }

  @Test
  @DisplayName("HLD 13: outbox metrics show the age of the oldest PENDING task and FAILED tasks")
  void givenPendingAndFailedTasks_whenReadingMetrics_thenOutboxGaugesReflectThem() {
    book(fixtures.patient().getId(), newSpecialtyWithDoctorAndRoom());
    jdbc.sql("UPDATE outbox_task SET created_at = now() - interval '2 minutes'").update();
    jdbc.sql("UPDATE outbox_task SET status = 'FAILED' WHERE type = 'EMAIL'").update();

    assertThat(measurement(OLDEST_PENDING_AGE, null, "VALUE")).isGreaterThanOrEqualTo(120);
    assertThat(measurement(FAILED, "type:EMAIL", "VALUE")).isEqualTo(1);
    assertThat(measurement(FAILED, "type:DOCTOR_CALENDAR", "VALUE")).isZero();
  }

  @Test
  @DisplayName("HLD 13: with nothing pending, the oldest PENDING age is 0")
  void givenNoPendingTasks_whenReadingMetrics_thenOldestPendingAgeIsZero() {
    assertThat(measurement(OLDEST_PENDING_AGE, null, "VALUE")).isZero();
  }

  @Test
  @DisplayName("Metrics require authentication")
  void givenNoCredentials_whenReadingMetrics_thenUnauthorized() {
    assertThat(mvc.get().uri("/actuator/metrics")).hasStatus(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @DisplayName("Readiness checks the database; liveness checks no dependency")
  void givenDatabaseUp_whenProbing_thenReadinessIncludesDatabaseAndLivenessDoesNot() {
    MvcTestResult readiness =
        mvc.get().uri("/actuator/health/readiness").headers(admin()).exchange();
    MvcTestResult liveness = mvc.get().uri("/actuator/health/liveness").headers(admin()).exchange();

    assertThat(readiness).hasStatusOk();
    assertThat(readiness).bodyJson().extractingPath("$.status").isEqualTo("UP");
    assertThat(readiness).bodyJson().extractingPath("$.components.db.status").isEqualTo("UP");
    assertThat(liveness).hasStatusOk();
    assertThat(liveness).bodyJson().extractingPath("$.status").isEqualTo("UP");
    assertThat(liveness).bodyJson().doesNotHavePath("$.components.db");
  }

  @Test
  @DisplayName("Probes are public but show no components without credentials")
  void givenNoCredentials_whenProbing_thenStatusOnly() {
    MvcTestResult readiness = mvc.get().uri("/actuator/health/readiness").exchange();

    assertThat(readiness).hasStatusOk();
    assertThat(readiness).bodyJson().isStrictlyEqualTo("{\"status\":\"UP\"}");
  }

  private Specialty newSpecialtyWithDoctorAndRoom() {
    Specialty specialty = fixtures.specialty();
    fixtures.doctors(specialty, 1);
    fixtures.rooms(1);
    return specialty;
  }

  private void book(UUID patientId, Specialty specialty) {
    mvc.post()
        .uri("/api/v1/appointments")
        .headers(headers -> headers.setBasicAuth("booking-client", "booking-test"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"patientId": "%s", "specialty": "%s", "startAt": "2035-03-05T14:00:00Z"}
            """
                .formatted(patientId, specialty.getCode()))
        .exchange();
  }

  /** 0 when the meter has not recorded anything with that tag yet (404 from Actuator). */
  private double count(String name, String tag) {
    return measurement(name, tag, "COUNT");
  }

  private double measurement(String name, String tag, String statistic) {
    MvcTestResult result = metric(name, tag);
    if (result.getResponse().getStatus() == HttpStatus.NOT_FOUND.value()) {
      return 0;
    }
    assertThat(result).hasStatusOk();
    List<Number> values =
        JsonPath.read(body(result), "$.measurements[?(@.statistic == '" + statistic + "')].value");
    return values.getFirst().doubleValue();
  }

  private List<String> availableTagValues(String name, String tag) {
    MvcTestResult result = metric(name, null);
    assertThat(result).hasStatusOk();
    List<List<String>> values =
        JsonPath.read(body(result), "$.availableTags[?(@.tag == '" + tag + "')].values");
    return values.getFirst();
  }

  private MvcTestResult metric(String name, String tag) {
    String uri = "/actuator/metrics/" + name + (tag == null ? "" : "?tag=" + tag);
    return mvc.get().uri(uri).headers(admin()).exchange();
  }

  private static String body(MvcTestResult result) {
    return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
  }

  private static HttpHeaders admin() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBasicAuth("admin", "admin-test");
    return headers;
  }
}
