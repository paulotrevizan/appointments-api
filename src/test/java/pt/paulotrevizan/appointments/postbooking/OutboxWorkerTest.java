package pt.paulotrevizan.appointments.postbooking;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import pt.paulotrevizan.appointments.TestcontainersConfiguration;
import pt.paulotrevizan.appointments.booking.persistence.AppointmentRepository;
import pt.paulotrevizan.appointments.catalog.persistence.DoctorRepository;
import pt.paulotrevizan.appointments.catalog.persistence.PatientRepository;
import pt.paulotrevizan.appointments.catalog.persistence.RoomRepository;
import pt.paulotrevizan.appointments.catalog.persistence.SpecialtyRepository;
import pt.paulotrevizan.appointments.postbooking.PostBookingFixtures.ReferenceData;
import pt.paulotrevizan.appointments.postbooking.application.PostBookingTasks;

@SpringBootTest(
    properties = {
      "appointments.outbox.worker.enabled=true",
      "appointments.outbox.poll-interval=100ms",
      "appointments.outbox.max-attempts=3",
      "appointments.outbox.backoff.initial=1s",
      "appointments.outbox.backoff.multiplier=2",
      "appointments.outbox.backoff.max=2s",
      "appointments.outbox.backoff.jitter=0ms",
      "spring.http.clients.read-timeout=500ms"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OutboxWorkerTest {

  private static final Instant START = Instant.parse("2035-03-05T14:00:00Z");
  private static final String RECOVERED = "recovered";

  private static final WireMockServer EXTERNAL_SYSTEMS =
      new WireMockServer(options().dynamicPort());

  static {
    EXTERNAL_SYSTEMS.start();
  }

  private final MockMvcTester mvc;
  private final JdbcClient jdbc;
  private final PostBookingFixtures fixtures;

  OutboxWorkerTest(
      @Autowired MockMvcTester mvc,
      @Autowired JdbcClient jdbc,
      @Autowired Clock clock,
      @Autowired PatientRepository patients,
      @Autowired SpecialtyRepository specialties,
      @Autowired DoctorRepository doctors,
      @Autowired RoomRepository rooms,
      @Autowired AppointmentRepository appointments,
      @Autowired PostBookingTasks postBookingTasks) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.fixtures =
        new PostBookingFixtures(
            patients, specialties, doctors, rooms, appointments, postBookingTasks, clock);
  }

  @DynamicPropertySource
  static void externalSystems(DynamicPropertyRegistry registry) {
    registry.add("spring.http.serviceclient.calendar.base-url", EXTERNAL_SYSTEMS::baseUrl);
    registry.add("spring.http.serviceclient.room.base-url", EXTERNAL_SYSTEMS::baseUrl);
  }

  @AfterAll
  static void stopExternalSystems() {
    EXTERNAL_SYSTEMS.stop();
  }

  @BeforeEach
  void reset() {
    EXTERNAL_SYSTEMS.resetAll();
    jdbc.sql("TRUNCATE outbox_task, appointment, doctor, room, patient, specialty CASCADE")
        .update();
  }

  @Test
  @DisplayName("I4: after a booking, calendar and room each get exactly one idempotent request")
  void givenBooking_whenWorkerRuns_thenCalendarAndRoomCalledOnceAndTasksDone() {
    ReferenceData data = fixtures.referenceData();
    stubCalendar(aResponse().withStatus(201));
    stubRoom(aResponse().withStatus(201));

    String appointmentId = book(data);

    awaitStatus(appointmentId, "DOCTOR_CALENDAR", "DONE");
    awaitStatus(appointmentId, "ROOM_RESERVATION", "DONE");
    EXTERNAL_SYSTEMS.verify(
        exactly(1),
        postRequestedFor(urlPathEqualTo("/doctors/" + data.doctor().getId() + "/events"))
            .withHeader("Idempotency-Key", equalTo(appointmentId))
            .withRequestBody(
                equalToJson(
                    """
                    {"appointmentId": "%s", "patientId": "%s", "startAt": "2035-03-05T14:00:00Z",
                     "endAt": "2035-03-05T14:30:00Z", "title": "Medical appointment"}
                    """
                        .formatted(appointmentId, data.patient().getId()))));
    EXTERNAL_SYSTEMS.verify(
        exactly(1),
        postRequestedFor(urlPathEqualTo("/rooms/" + data.room().getId() + "/reservations"))
            .withHeader("Idempotency-Key", equalTo(appointmentId))
            .withRequestBody(
                equalToJson(
                    """
                    {"appointmentId": "%s", "startAt": "2035-03-05T14:00:00Z",
                     "endAt": "2035-03-05T14:30:00Z"}
                    """
                        .formatted(appointmentId))));
    assertThat(task(appointmentId, "EMAIL").get("status")).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("D-001: booking still succeeds while both external systems are down")
  void givenExternalSystemsDown_whenBooking_thenCreatedAndTasksRescheduled() {
    ReferenceData data = fixtures.referenceData();
    stubCalendar(aResponse().withStatus(503));
    stubRoom(aResponse().withStatus(503));

    String appointmentId = book(data);

    for (String type : new String[] {"DOCTOR_CALENDAR", "ROOM_RESERVATION"}) {
      await()
          .atMost(5, SECONDS)
          .untilAsserted(
              () ->
                  assertThat(
                          jdbc.sql(
                                  """
                                  SELECT status = 'PENDING' AND attempts >= 1
                                     AND next_attempt_at > now() AND last_error LIKE '%503%'
                                  FROM outbox_task
                                  WHERE appointment_id = :id AND type = :type
                                  """)
                              .param("id", UUID.fromString(appointmentId))
                              .param("type", type)
                              .query(Boolean.class)
                              .single())
                      .isTrue());
    }
  }

  @ParameterizedTest(name = "first call: {0}")
  @ValueSource(strings = {"HTTP 503", "timeout"})
  @DisplayName("D-005: a transient failure is retried with backoff and the task ends DONE")
  void givenTransientFailure_whenSystemRecovers_thenTaskDoneAfterRetry(String failure) {
    ReferenceData data = fixtures.referenceData();
    ResponseDefinitionBuilder firstResponse =
        failure.equals("timeout")
            ? aResponse().withStatus(201).withFixedDelay(1_500)
            : aResponse().withStatus(503);
    EXTERNAL_SYSTEMS.stubFor(
        post(urlPathEqualTo("/doctors/" + data.doctor().getId() + "/events"))
            .inScenario("calendar")
            .whenScenarioStateIs(STARTED)
            .willReturn(firstResponse)
            .willSetStateTo(RECOVERED));
    EXTERNAL_SYSTEMS.stubFor(
        post(urlPathEqualTo("/doctors/" + data.doctor().getId() + "/events"))
            .inScenario("calendar")
            .whenScenarioStateIs(RECOVERED)
            .willReturn(aResponse().withStatus(201)));
    stubRoom(aResponse().withStatus(201));

    String appointmentId = book(data);

    awaitStatus(appointmentId, "DOCTOR_CALENDAR", "DONE");
    assertThat(task(appointmentId, "DOCTOR_CALENDAR").get("attempts")).isEqualTo(1);
  }

  @Test
  @DisplayName("D-017: a 4xx rejection fails the task at once, without retrying")
  void givenRejection_whenWorkerRuns_thenTaskFailedImmediately() {
    ReferenceData data = fixtures.referenceData();
    stubCalendar(aResponse().withStatus(201));
    stubRoom(aResponse().withStatus(422));

    String appointmentId = book(data);

    awaitStatus(appointmentId, "ROOM_RESERVATION", "FAILED");
    Map<String, Object> task = task(appointmentId, "ROOM_RESERVATION");
    assertThat(task.get("attempts")).isEqualTo(1);
    assertThat((String) task.get("last_error")).contains("HTTP 422");
    EXTERNAL_SYSTEMS.verify(exactly(1), postRequestedFor(urlPathEqualTo(roomPath(data))));
  }

  @Test
  @DisplayName("D-005: a task that keeps failing is FAILED after the maximum number of attempts")
  void givenSystemAlwaysDown_whenMaxAttemptsReached_thenTaskFailed() {
    ReferenceData data = fixtures.referenceData();
    stubCalendar(aResponse().withStatus(201));
    stubRoom(aResponse().withStatus(503));

    String appointmentId = book(data);

    awaitStatus(appointmentId, "ROOM_RESERVATION", "FAILED");
    assertThat(task(appointmentId, "ROOM_RESERVATION").get("attempts")).isEqualTo(3);
    EXTERNAL_SYSTEMS.verify(exactly(3), postRequestedFor(urlPathEqualTo(roomPath(data))));
  }

  private String book(ReferenceData data) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBasicAuth("booking-client", "booking-test");
    MvcTestResult result =
        mvc.post()
            .uri("/api/v1/appointments")
            .headers(headers)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"patientId\":\"%s\",\"specialty\":\"%s\",\"startAt\":\"%s\"}"
                    .formatted(data.patient().getId(), data.specialty().getCode(), START))
            .exchange();
    assertThat(result).hasStatus(HttpStatus.CREATED);
    try {
      return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private void awaitStatus(String appointmentId, String type, String status) {
    await()
        .atMost(10, SECONDS)
        .untilAsserted(() -> assertThat(task(appointmentId, type).get("status")).isEqualTo(status));
  }

  private Map<String, Object> task(String appointmentId, String type) {
    return jdbc.sql(
            "SELECT status, attempts, last_error FROM outbox_task"
                + " WHERE appointment_id = :id AND type = :type")
        .param("id", UUID.fromString(appointmentId))
        .param("type", type)
        .query()
        .singleRow();
  }

  private void stubCalendar(ResponseDefinitionBuilder response) {
    EXTERNAL_SYSTEMS.stubFor(post(urlPathMatching("/doctors/.*/events")).willReturn(response));
  }

  private void stubRoom(ResponseDefinitionBuilder response) {
    EXTERNAL_SYSTEMS.stubFor(post(urlPathMatching("/rooms/.*/reservations")).willReturn(response));
  }

  private static String roomPath(ReferenceData data) {
    return "/rooms/" + data.room().getId() + "/reservations";
  }
}
