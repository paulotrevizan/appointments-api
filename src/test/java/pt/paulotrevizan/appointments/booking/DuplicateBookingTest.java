package pt.paulotrevizan.appointments.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UnsupportedEncodingException;
import java.time.Clock;
import java.time.Instant;
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
import pt.paulotrevizan.appointments.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DuplicateBookingTest {

  private static final Instant START = Instant.parse("2035-03-05T14:00:00Z");

  private final MockMvcTester mvc;
  private final JdbcClient jdbc;
  private final AppointmentRepository appointments;
  private final BookingFixtures fixtures;
  private final Clock clock;

  DuplicateBookingTest(
      @Autowired MockMvcTester mvc,
      @Autowired JdbcClient jdbc,
      @Autowired Clock clock,
      @Autowired PatientRepository patients,
      @Autowired SpecialtyRepository specialties,
      @Autowired DoctorRepository doctors,
      @Autowired RoomRepository rooms,
      @Autowired AppointmentRepository appointments) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.clock = clock;
    this.appointments = appointments;
    this.fixtures = new BookingFixtures(patients, specialties, doctors, rooms, clock);
  }

  @BeforeEach
  void emptyDatabase() {
    jdbc.sql("TRUNCATE outbox_task, appointment, doctor, room, patient, specialty CASCADE")
        .update();
  }

  @Test
  @DisplayName(
      "D-011: repeating a request returns 200 with the same appointment, nothing new is created")
  void givenBookedAppointment_whenSameRequestRepeated_thenOkWithSameBody() {
    Specialty specialty = fixtures.specialty();
    fixtures.doctors(specialty, 2);
    fixtures.rooms(2);
    Patient patient = fixtures.patient();

    MvcTestResult first = book(patient, specialty);
    MvcTestResult second = book(patient, specialty);

    assertThat(first).hasStatus(HttpStatus.CREATED);
    assertThat(second).hasStatus(HttpStatus.OK);
    assertThat(second).bodyText().isEqualTo(bodyOf(first));
    assertThat(count("appointment")).isEqualTo(1);
    assertThat(count("outbox_task")).isEqualTo(3);
  }

  @Test
  @DisplayName(
      "D-011: the same slot with another specialty is rejected with PATIENT_ALREADY_BOOKED")
  void givenBookedAppointment_whenSameSlotOtherSpecialty_thenPatientAlreadyBooked() {
    Specialty cardiology = fixtures.specialty();
    Specialty dermatology = fixtures.specialty();
    fixtures.doctors(cardiology, 1);
    fixtures.doctors(dermatology, 1);
    fixtures.rooms(2);
    Patient patient = fixtures.patient();
    book(patient, cardiology);

    MvcTestResult result = book(patient, dermatology);

    assertThat(result).hasStatus(HttpStatus.CONFLICT);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("PATIENT_ALREADY_BOOKED");
    assertThat(count("appointment")).isEqualTo(1);
  }

  @Test
  @DisplayName("A cancelled appointment does not count as a duplicate")
  void givenCancelledAppointment_whenSameRequestRepeated_thenNewAppointmentCreated() {
    Specialty specialty = fixtures.specialty();
    fixtures.doctors(specialty, 1);
    fixtures.rooms(1);
    Patient patient = fixtures.patient();
    book(patient, specialty);
    Appointment first = appointments.findAll().get(0);
    first.cancel(clock.instant());
    appointments.saveAndFlush(first);

    MvcTestResult second = book(patient, specialty);

    assertThat(second).hasStatus(HttpStatus.CREATED);
    assertThat(count("appointment")).isEqualTo(2);
  }

  private MvcTestResult book(Patient patient, Specialty specialty) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBasicAuth("booking-client", "booking-test");
    String body =
        "{\"patientId\":\"%s\",\"specialty\":\"%s\",\"startAt\":\"%s\"}"
            .formatted(patient.getId(), specialty.getCode(), START);
    return mvc.post()
        .uri("/api/v1/appointments")
        .headers(headers)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  private static String bodyOf(MvcTestResult result) {
    try {
      return result.getResponse().getContentAsString();
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private int count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
  }
}
