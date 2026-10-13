package pt.paulotrevizan.appointments.postbooking;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.created;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import pt.paulotrevizan.appointments.TestcontainersConfiguration;
import pt.paulotrevizan.appointments.booking.BookingFixtures;
import pt.paulotrevizan.appointments.catalog.domain.Patient;
import pt.paulotrevizan.appointments.catalog.domain.Specialty;
import pt.paulotrevizan.appointments.catalog.persistence.DoctorRepository;
import pt.paulotrevizan.appointments.catalog.persistence.PatientRepository;
import pt.paulotrevizan.appointments.catalog.persistence.RoomRepository;
import pt.paulotrevizan.appointments.catalog.persistence.SpecialtyRepository;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskType;

@SpringBootTest(
    properties = {
      "appointments.outbox.enabled=true",
      "appointments.outbox.poll-interval=100ms",
      "appointments.outbox.initial-backoff=100ms",
      "appointments.outbox.max-backoff=300ms",
      "appointments.outbox.max-attempts=" + OutboxWorkerTest.MAX_ATTEMPTS,
      "appointments.integration.read-timeout=500ms"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OutboxWorkerTest {

  static final int MAX_ATTEMPTS = 6;
  private static final String CALENDAR_PATH = "/doctors/.*/events";
  private static final String ROOMS_PATH = "/rooms/.*/reservations";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @RegisterExtension
  static final WireMockExtension externals =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void externalSystems(DynamicPropertyRegistry registry) {
    registry.add("appointments.integration.calendar-base-url", externals::baseUrl);
    registry.add("appointments.integration.rooms-base-url", externals::baseUrl);
  }

  private final MockMvcTester mvc;
  private final JdbcClient jdbc;
  private final BookingFixtures fixtures;

  OutboxWorkerTest(
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
  @DisplayName(
      "I4: after a booking, calendar and room each get one request keyed by the appointment id")
  void givenBooking_whenSystemsAccept_thenOneIdempotentRequestEachAndTasksDone() {
    stubBoth(created());

    Booking booking = book();

    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () -> {
              assertThat(task(booking, OutboxTaskType.DOCTOR_CALENDAR).status()).isEqualTo("DONE");
              assertThat(task(booking, OutboxTaskType.ROOM_RESERVATION).status()).isEqualTo("DONE");
            });
    externals.verify(
        1,
        postRequestedFor(urlEqualTo("/doctors/" + booking.doctorId() + "/events"))
            .withHeader("Idempotency-Key", equalTo(booking.id()))
            .withRequestBody(
                equalToJson(
                    """
                    {"appointmentId": "%s", "startAt": "2035-03-05T14:00:00Z",
                     "endAt": "2035-03-05T14:30:00Z", "title": "Appointment in %s"}
                    """
                        .formatted(booking.id(), booking.roomName()))));
    externals.verify(
        1,
        postRequestedFor(urlEqualTo("/rooms/" + booking.roomId() + "/reservations"))
            .withHeader("Idempotency-Key", equalTo(booking.id()))
            .withRequestBody(
                equalToJson(
                    """
                    {"appointmentId": "%s", "startAt": "2035-03-05T14:00:00Z",
                     "endAt": "2035-03-05T14:30:00Z"}
                    """
                        .formatted(booking.id()))));
    externals.verify(2, postRequestedFor(urlPathMatching("/.*")));
    assertThat(task(booking, OutboxTaskType.EMAIL).status()).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("D-001: booking returns 201 while both external systems are down")
  void givenBothSystemsDown_whenBooking_thenCreatedAndTasksKeptForRetry() {
    stubBoth(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

    Booking booking = book();

    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () -> {
              TaskRow calendar = task(booking, OutboxTaskType.DOCTOR_CALENDAR);
              TaskRow room = task(booking, OutboxTaskType.ROOM_RESERVATION);
              assertThat(calendar.attempts()).isPositive();
              assertThat(calendar.status()).isNotEqualTo("DONE");
              assertThat(room.attempts()).isPositive();
              assertThat(room.status()).isNotEqualTo("DONE");
            });
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Outage.class)
  @DisplayName("D-017: a 503 or a timeout keeps the task PENDING with a later next attempt")
  void givenTransientFailure_whenTaskRuns_thenPendingWithRetryScheduled(Outage outage) {
    stubBoth(outage.response());

    Booking booking = book();

    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () -> {
              TaskRow calendar = task(booking, OutboxTaskType.DOCTOR_CALENDAR);
              assertThat(calendar.attempts()).isPositive();
              assertThat(calendar.status()).isEqualTo("PENDING");
              assertThat(calendar.retryScheduled()).isTrue();
              assertThat(calendar.lastError()).isNotBlank();
            });
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Outage.class)
  @DisplayName("I4: after a 503 or a timeout, the task is DONE once the system recovers")
  void givenTransientFailure_whenSystemRecovers_thenDone(Outage outage) {
    failOnceThenAccept(CALENDAR_PATH, outage.response());
    failOnceThenAccept(ROOMS_PATH, outage.response());

    Booking booking = book();

    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () -> {
              TaskRow calendar = task(booking, OutboxTaskType.DOCTOR_CALENDAR);
              TaskRow room = task(booking, OutboxTaskType.ROOM_RESERVATION);
              assertThat(calendar.status()).isEqualTo("DONE");
              assertThat(calendar.attempts()).isEqualTo(2);
              assertThat(calendar.lastError()).isNull();
              assertThat(room.status()).isEqualTo("DONE");
              assertThat(room.attempts()).isEqualTo(2);
            });
  }

  @Test
  @DisplayName("D-017: an external 4xx fails the task immediately and keeps the error")
  void givenClientError_whenTaskRuns_thenFailedWithoutRetry() {
    stubBoth(aResponse().withStatus(409).withBody("{\"error\":\"room closed\"}"));

    Booking booking = book();

    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () ->
                assertThat(task(booking, OutboxTaskType.ROOM_RESERVATION).status())
                    .isEqualTo("FAILED"));
    TaskRow room = task(booking, OutboxTaskType.ROOM_RESERVATION);
    assertThat(room.attempts()).isOne();
    assertThat(room.lastError()).contains("409").contains("room closed");
    externals.verify(
        1, postRequestedFor(urlEqualTo("/rooms/" + booking.roomId() + "/reservations")));
  }

  @Test
  @DisplayName("D-005: a task that keeps failing is FAILED after the maximum attempts")
  void givenPersistentOutage_whenMaxAttemptsReached_thenFailed() {
    stubBoth(serviceUnavailable());

    Booking booking = book();

    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () ->
                assertThat(task(booking, OutboxTaskType.DOCTOR_CALENDAR).status())
                    .isEqualTo("FAILED"));
    assertThat(task(booking, OutboxTaskType.DOCTOR_CALENDAR).attempts()).isEqualTo(MAX_ATTEMPTS);
    externals.verify(
        MAX_ATTEMPTS, postRequestedFor(urlEqualTo("/doctors/" + booking.doctorId() + "/events")));
  }

  enum Outage {
    HTTP_503,
    TIMEOUT;

    ResponseDefinitionBuilder response() {
      return switch (this) {
        case HTTP_503 -> serviceUnavailable();
        case TIMEOUT -> created().withFixedDelay(1_500);
      };
    }
  }

  record Booking(String id, String doctorId, String roomId, String roomName) {}

  record TaskRow(String status, int attempts, String lastError, boolean retryScheduled) {}

  private void stubBoth(ResponseDefinitionBuilder response) {
    externals.stubFor(post(urlPathMatching(CALENDAR_PATH)).willReturn(response));
    externals.stubFor(post(urlPathMatching(ROOMS_PATH)).willReturn(response));
  }

  private void failOnceThenAccept(String path, ResponseDefinitionBuilder failure) {
    externals.stubFor(
        post(urlPathMatching(path))
            .inScenario(path)
            .whenScenarioStateIs(STARTED)
            .willReturn(failure)
            .willSetStateTo("recovered"));
    externals.stubFor(
        post(urlPathMatching(path))
            .inScenario(path)
            .whenScenarioStateIs("recovered")
            .willReturn(created()));
  }

  private Booking book() {
    Specialty specialty = fixtures.specialty();
    fixtures.doctors(specialty, 1);
    fixtures.rooms(1);
    Patient patient = fixtures.patient();
    MvcTestResult result =
        mvc.post()
            .uri("/api/v1/appointments")
            .headers(headers -> headers.setBasicAuth("booking-client", "booking-test"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"patientId": "%s", "specialty": "%s", "startAt": "2035-03-05T14:00:00Z"}
                """
                    .formatted(patient.getId(), specialty.getCode()))
            .exchange();
    assertThat(result).hasStatus(HttpStatus.CREATED);
    DocumentContext body =
        JsonPath.parse(
            new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    return new Booking(
        body.read("$.id"),
        body.read("$.doctor.id"),
        body.read("$.room.id"),
        body.read("$.room.name"));
  }

  private TaskRow task(Booking booking, OutboxTaskType type) {
    return jdbc.sql(
            """
            SELECT status, attempts, last_error, next_attempt_at > updated_at AS retry_scheduled
            FROM outbox_task
            WHERE appointment_id = CAST(:appointmentId AS uuid) AND type = :type
            """)
        .param("appointmentId", booking.id())
        .param("type", type.name())
        .query(TaskRow.class)
        .single();
  }
}
