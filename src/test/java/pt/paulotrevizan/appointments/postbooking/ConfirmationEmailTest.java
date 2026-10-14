package pt.paulotrevizan.appointments.postbooking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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

@SpringBootTest(
    properties = {
      "appointments.outbox.enabled=true",
      "appointments.outbox.poll-interval=100ms",
      "appointments.outbox.initial-backoff=100ms",
      "appointments.outbox.max-backoff=300ms",
      "appointments.outbox.max-attempts=10",
      // Calendar and room are not under test: keep their failing calls short.
      "spring.http.clients.connect-timeout=200ms"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ConfirmationEmailTest {

  // Fixed for the whole class: tests stop and restart the SMTP server on the same port.
  private static final int SMTP_PORT = freePort();
  private static final Duration TIMEOUT = Duration.ofSeconds(15);

  @RegisterExtension
  static final GreenMailExtension smtp =
      new GreenMailExtension(new ServerSetup(SMTP_PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP))
          .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());

  @DynamicPropertySource
  static void smtpServer(DynamicPropertyRegistry registry) {
    registry.add("spring.mail.port", () -> SMTP_PORT);
  }

  private final MockMvcTester mvc;
  private final JdbcClient jdbc;
  private final BookingFixtures fixtures;

  ConfirmationEmailTest(
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
      "I4: after a booking, the patient gets one email with doctor, room and time, no specialty")
  void givenBooking_whenWorkerRuns_thenOneEmailWithDoctorRoomAndTimeButNoSpecialty()
      throws MessagingException {
    Booking booking = book();

    await()
        .atMost(TIMEOUT)
        .untilAsserted(() -> assertThat(emailTask(booking).status()).isEqualTo("DONE"));
    MimeMessage[] received = smtp.getReceivedMessages();
    assertThat(received).hasSize(1);
    MimeMessage email = received[0];
    assertThat(email.getAllRecipients())
        .extracting(Address::toString)
        .containsExactly(booking.patientEmail());
    assertThat(email.getFrom())
        .extracting(Address::toString)
        .containsExactly("appointments@clinic.test");
    assertThat(email.getSubject()).isEqualTo("Your appointment is confirmed");
    assertThat(GreenMailUtil.getBody(email))
        .contains("Hello Ana Souza,")
        .contains("Date: Monday, 5 March 2035")
        .contains("Time: 14:00 - 14:30 (Lisbon time)")
        .contains("Doctor: " + booking.doctorName())
        .contains("Room: " + booking.roomName())
        .contains("Reference: " + booking.id())
        .doesNotContain(booking.specialtyCode())
        .doesNotContainIgnoringCase("specialty");
  }

  @Test
  @DisplayName("D-027: the email shows Lisbon time: summer time is UTC+1, even across midnight")
  void givenSummerSlotLateInTheUtcDay_whenEmailSent_thenLisbonDateAndTime() {
    // 23:30 UTC in July is 00:30 the next day in Lisbon (WEST, UTC+1).
    Booking booking = book(Instant.parse("2035-07-10T23:30:00Z"));

    await()
        .atMost(TIMEOUT)
        .untilAsserted(() -> assertThat(emailTask(booking).status()).isEqualTo("DONE"));
    assertThat(GreenMailUtil.getBody(smtp.getReceivedMessages()[0]))
        .contains("Date: Wednesday, 11 July 2035")
        .contains("Time: 00:30 - 01:00 (Lisbon time)");
  }

  @Test
  @DisplayName("I4: while SMTP is unavailable the email is retried, then sent once it recovers")
  void givenSmtpUnavailable_whenItRecovers_thenEmailSentAndTaskDone() {
    smtp.stop();

    Booking booking = book();

    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () -> {
              TaskRow task = emailTask(booking);
              assertThat(task.attempts()).isPositive();
              assertThat(task.status()).isEqualTo("PENDING");
              assertThat(task.retryScheduled()).isTrue();
            });
    smtp.start();
    await()
        .atMost(TIMEOUT)
        .untilAsserted(() -> assertThat(emailTask(booking).status()).isEqualTo("DONE"));
    assertThat(smtp.getReceivedMessages()).hasSize(1);
  }

  @Test
  @DisplayName("D-017: a permanent SMTP rejection fails the email task without retrying")
  void givenPermanentSmtpRejection_whenSending_thenFailedWithoutRetry() throws IOException {
    smtp.stop();

    try (RejectingSmtpServer rejecting = new RejectingSmtpServer(SMTP_PORT)) {
      Booking booking = book();

      await()
          .atMost(TIMEOUT)
          .untilAsserted(() -> assertThat(emailTask(booking).status()).isEqualTo("FAILED"));
      TaskRow task = emailTask(booking);
      assertThat(task.attempts()).isOne();
      assertThat(task.lastError()).contains("550");
    }
  }

  record Booking(
      String id, String patientEmail, String doctorName, String roomName, String specialtyCode) {}

  record TaskRow(String status, int attempts, String lastError, boolean retryScheduled) {}

  private Booking book() {
    return book(Instant.parse("2035-03-05T14:00:00Z"));
  }

  private Booking book(Instant startAt) {
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
                {"patientId": "%s", "specialty": "%s", "startAt": "%s"}
                """
                    .formatted(patient.getId(), specialty.getCode(), startAt))
            .exchange();
    assertThat(result).hasStatus(HttpStatus.CREATED);
    DocumentContext body =
        JsonPath.parse(
            new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    return new Booking(
        body.read("$.id"),
        patient.getEmail(),
        body.read("$.doctor.name"),
        body.read("$.room.name"),
        specialty.getCode());
  }

  private TaskRow emailTask(Booking booking) {
    return jdbc.sql(
            """
            SELECT status, attempts, last_error, next_attempt_at > updated_at AS retry_scheduled
            FROM outbox_task
            WHERE appointment_id = CAST(:appointmentId AS uuid) AND type = 'EMAIL'
            """)
        .param("appointmentId", booking.id())
        .query(TaskRow.class)
        .single();
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
