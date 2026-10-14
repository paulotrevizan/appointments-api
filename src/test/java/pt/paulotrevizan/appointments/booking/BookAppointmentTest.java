package pt.paulotrevizan.appointments.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import pt.paulotrevizan.appointments.TestcontainersConfiguration;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.booking.persistence.AppointmentRepository;
import pt.paulotrevizan.appointments.catalog.domain.Doctor;
import pt.paulotrevizan.appointments.catalog.domain.Patient;
import pt.paulotrevizan.appointments.catalog.domain.Room;
import pt.paulotrevizan.appointments.catalog.domain.Specialty;
import pt.paulotrevizan.appointments.catalog.persistence.DoctorRepository;
import pt.paulotrevizan.appointments.catalog.persistence.PatientRepository;
import pt.paulotrevizan.appointments.catalog.persistence.RoomRepository;
import pt.paulotrevizan.appointments.catalog.persistence.SpecialtyRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BookAppointmentTest {

  private static final String URI = "/api/v1/appointments";
  private static final Duration SLOT = Duration.ofMinutes(30);
  private static final Instant START = Instant.parse("2035-03-05T14:00:00Z");

  private final MockMvcTester mvc;
  private final JdbcClient jdbc;
  private final Clock clock;
  private final PatientRepository patients;
  private final SpecialtyRepository specialties;
  private final DoctorRepository doctors;
  private final RoomRepository rooms;
  private final AppointmentRepository appointments;

  BookAppointmentTest(
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
    this.patients = patients;
    this.specialties = specialties;
    this.doctors = doctors;
    this.rooms = rooms;
    this.appointments = appointments;
  }

  @BeforeEach
  void emptyDatabase() {
    jdbc.sql("TRUNCATE outbox_task, appointment, doctor, room, patient, specialty CASCADE")
        .update();
  }

  @Test
  @DisplayName(
      "I3: a booking is confirmed with a doctor of the requested specialty, a room and the patient")
  void givenFreeDoctorAndRoom_whenBooking_thenCreatedWithDoctorOfRequestedSpecialty() {
    Specialty cardiology = newSpecialty();
    Specialty dermatology = newSpecialty();
    Doctor cardiologist = newDoctor(cardiology);
    newDoctor(dermatology);
    Room room = newRoom();
    Patient patient = newPatient();

    MvcTestResult result = book(patient.getId(), cardiology.getCode(), START, bookingClient());

    assertThat(result).hasStatus(HttpStatus.CREATED);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.doctor.id")
        .isEqualTo(cardiologist.getId().toString());
    assertThat(result).bodyJson().extractingPath("$.doctor.name").isEqualTo("Dr. Test");
    assertThat(result).bodyJson().extractingPath("$.room.id").isEqualTo(room.getId().toString());
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED");
    assertThat(result).bodyJson().extractingPath("$.specialty").isEqualTo(cardiology.getCode());
    assertThat(result).bodyJson().extractingPath("$.startAt").isEqualTo("2035-03-05T14:00:00Z");
    assertThat(result).bodyJson().extractingPath("$.endAt").isEqualTo("2035-03-05T14:30:00Z");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.patient.id")
        .isEqualTo(patient.getId().toString());
    assertThat(result).bodyJson().extractingPath("$.patient.email").isEqualTo(patient.getEmail());
    assertThat(result).bodyJson().extractingPath("$.patient.phone").isEqualTo(patient.getPhone());
    assertThat(result).bodyJson().extractingPath("$.createdAt").isNotNull();
  }

  @Test
  @DisplayName(
      "D-015: the 201 carries the whole appointment and no Location: there is no read-by-id")
  void givenBooking_whenCreated_thenNoLocationHeaderAndNoReadByIdEndpoint() {
    Specialty specialty = newSpecialty();
    newDoctor(specialty);
    newRoom();

    MvcTestResult result = book(newPatient().getId(), specialty.getCode(), START, bookingClient());

    assertThat(result).hasStatus(HttpStatus.CREATED);
    assertThat(result).headers().doesNotContainHeader(HttpHeaders.LOCATION);
    String id =
        JsonPath.read(
            new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8),
            "$.id");
    assertThat(mvc.get().uri(URI + "/" + id).headers(bookingClient()))
        .hasStatus(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName(
      "I4: the new appointment has exactly three PENDING outbox tasks: calendar, room, email")
  void givenBooking_whenCreated_thenThreePendingOutboxTasks() {
    Specialty specialty = newSpecialty();
    newDoctor(specialty);
    newRoom();
    Patient patient = newPatient();

    MvcTestResult result = book(patient.getId(), specialty.getCode(), START, bookingClient());

    assertThat(result).hasStatus(HttpStatus.CREATED);
    List<Map<String, Object>> tasks =
        jdbc.sql("SELECT type, status FROM outbox_task").query().listOfRows();
    assertThat(tasks)
        .extracting(task -> task.get("type"), task -> task.get("status"))
        .containsExactlyInAnyOrder(
            tuple("DOCTOR_CALENDAR", "PENDING"),
            tuple("ROOM_RESERVATION", "PENDING"),
            tuple("EMAIL", "PENDING"));
  }

  @Test
  @DisplayName("D-009: with two free doctors, the one with fewer appointments that day is chosen")
  void givenTwoFreeDoctors_whenOneHasAppointmentsThatDay_thenTheOtherIsChosen() {
    Specialty specialty = newSpecialty();
    List<Doctor> byId = sortedByDatabaseOrder(newDoctor(specialty), newDoctor(specialty));
    Doctor lowerId = byId.get(0);
    Doctor higherId = byId.get(1);
    Room room = newRoom();
    // The doctor who would win the id tie-break is already busy that day.
    save(newPatient(), specialty, lowerId, room, Instant.parse("2035-03-05T10:00:00Z"));

    MvcTestResult result = book(newPatient().getId(), specialty.getCode(), START, bookingClient());

    assertThat(result).hasStatus(HttpStatus.CREATED);
    assertThat(result)
        .bodyJson()
        .extractingPath("$.doctor.id")
        .isEqualTo(higherId.getId().toString());
  }

  @Test
  @DisplayName("D-009: with equally loaded doctors, the lowest id is chosen")
  void givenTwoEquallyLoadedDoctors_whenBooking_thenLowestIdIsChosen() {
    Specialty specialty = newSpecialty();
    Doctor first = newDoctor(specialty);
    Doctor second = newDoctor(specialty);
    newRoom();
    UUID lowest = sortedByDatabaseOrder(first, second).get(0).getId();

    MvcTestResult result = book(newPatient().getId(), specialty.getCode(), START, bookingClient());

    assertThat(result).hasStatus(HttpStatus.CREATED);
    assertThat(result).bodyJson().extractingPath("$.doctor.id").isEqualTo(lowest.toString());
  }

  @Test
  @DisplayName("A slot in the past is rejected with SLOT_IN_PAST")
  void givenPastSlot_whenBooking_thenSlotInPast() {
    MvcTestResult result =
        book(UUID.randomUUID(), "ANY", Instant.parse("2020-01-06T14:00:00Z"), bookingClient());

    assertProblem(result, HttpStatus.BAD_REQUEST, "SLOT_IN_PAST");
  }

  @Test
  @DisplayName("A slot off the 30-minute grid is rejected with SLOT_NOT_ALIGNED")
  void givenUnalignedSlot_whenBooking_thenSlotNotAligned() {
    MvcTestResult result =
        book(UUID.randomUUID(), "ANY", Instant.parse("2035-03-05T14:15:00Z"), bookingClient());

    assertProblem(result, HttpStatus.BAD_REQUEST, "SLOT_NOT_ALIGNED");
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(
      strings = {
        "{}",
        "not json",
        "{\"patientId\":\"not-a-uuid\",\"specialty\":\"X\",\"startAt\":\"2035-03-05T14:00:00Z\"}",
        "{\"patientId\":\"00000000-0000-0000-0000-000000000001\",\"specialty\":\"\",\"startAt\":\"2035-03-05T14:00:00Z\"}",
        "{\"patientId\":\"00000000-0000-0000-0000-000000000001\",\"specialty\":\"X\",\"startAt\":\"tomorrow\"}",
        "{\"patientId\":\"00000000-0000-0000-0000-000000000001\",\"specialty\":\"X\"}"
      })
  @DisplayName("Missing or invalid fields are rejected with VALIDATION_ERROR")
  void givenInvalidBody_whenBooking_thenValidationError(String body) {
    MvcTestResult result =
        mvc.post()
            .uri(URI)
            .headers(bookingClient())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .exchange();

    assertProblem(result, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
  }

  @Test
  @DisplayName("An unknown patient is rejected with PATIENT_NOT_FOUND")
  void givenUnknownPatient_whenBooking_thenPatientNotFound() {
    Specialty specialty = newSpecialty();

    MvcTestResult result = book(UUID.randomUUID(), specialty.getCode(), START, bookingClient());

    assertProblem(result, HttpStatus.NOT_FOUND, "PATIENT_NOT_FOUND");
  }

  @Test
  @DisplayName("An unknown specialty is rejected with SPECIALTY_NOT_FOUND")
  void givenUnknownSpecialty_whenBooking_thenSpecialtyNotFound() {
    Patient patient = newPatient();

    MvcTestResult result = book(patient.getId(), "NO_SUCH_SPECIALTY", START, bookingClient());

    assertProblem(result, HttpStatus.NOT_FOUND, "SPECIALTY_NOT_FOUND");
  }

  @Test
  @DisplayName(
      "I1: when every doctor of the specialty is booked, the request gets NO_DOCTOR_AVAILABLE")
  void givenAllDoctorsBooked_whenBooking_thenNoDoctorAvailable() {
    Specialty specialty = newSpecialty();
    Doctor doctor = newDoctor(specialty);
    newRoom();
    save(newPatient(), specialty, doctor, newRoom(), START);

    MvcTestResult result = book(newPatient().getId(), specialty.getCode(), START, bookingClient());

    assertProblem(result, HttpStatus.CONFLICT, "NO_DOCTOR_AVAILABLE");
  }

  @Test
  @DisplayName("I2: when every room is booked, the request gets NO_ROOM_AVAILABLE")
  void givenAllRoomsBooked_whenBooking_thenNoRoomAvailable() {
    Specialty specialty = newSpecialty();
    Specialty other = newSpecialty();
    newDoctor(specialty);
    Room onlyRoom = newRoom();
    save(newPatient(), other, newDoctor(other), onlyRoom, START);

    MvcTestResult result = book(newPatient().getId(), specialty.getCode(), START, bookingClient());

    assertProblem(result, HttpStatus.CONFLICT, "NO_ROOM_AVAILABLE");
  }

  @Test
  @DisplayName("A request without credentials gets 401")
  void givenNoCredentials_whenBooking_thenUnauthorized() {
    MvcTestResult result = book(UUID.randomUUID(), "ANY", START, new HttpHeaders());

    assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
    assertThat(result).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).headers().containsHeader(HttpHeaders.WWW_AUTHENTICATE);
  }

  @Test
  @DisplayName("A request with wrong credentials gets 401")
  void givenWrongPassword_whenBooking_thenUnauthorized() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBasicAuth("booking-client", "wrong");

    MvcTestResult result = book(UUID.randomUUID(), "ANY", START, headers);

    assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @DisplayName("D-012: ADMIN credentials cannot book (403)")
  void givenAdminCredentials_whenBooking_thenForbidden() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBasicAuth("admin", "admin-test");

    MvcTestResult result = book(UUID.randomUUID(), "ANY", START, headers);

    assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
    assertThat(result).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
  }

  @Test
  @DisplayName("D-012: BOOKING_CLIENT credentials cannot reach the admin listing (403)")
  void givenBookingClientCredentials_whenCallingAdminEndpoint_thenForbidden() {
    MvcTestResult result =
        mvc.get().uri("/api/v1/admin/appointments").headers(bookingClient()).exchange();

    assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
  }

  private void assertProblem(MvcTestResult result, HttpStatus status, String code) {
    assertThat(result).hasStatus(status);
    assertThat(result).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
    assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status.value());
    assertThat(result).bodyJson().extractingPath("$.title").isEqualTo(status.getReasonPhrase());
    assertThat(result).bodyJson().extractingPath("$.detail").isNotNull();
    assertThat(result).bodyJson().doesNotHavePath("$.type");
    assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo(URI);
  }

  private MvcTestResult book(UUID patientId, String specialty, Instant startAt, HttpHeaders auth) {
    String body =
        "{\"patientId\":\"%s\",\"specialty\":\"%s\",\"startAt\":\"%s\"}"
            .formatted(patientId, specialty, startAt);
    return mvc.post()
        .uri(URI)
        .headers(auth)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  // PostgreSQL orders uuid as unsigned bytes, like the lowercase hex text (java.util.UUID does
  // not).
  private static List<Doctor> sortedByDatabaseOrder(Doctor first, Doctor second) {
    return Stream.of(first, second)
        .sorted(Comparator.comparing(doctor -> doctor.getId().toString()))
        .toList();
  }

  private static HttpHeaders bookingClient() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBasicAuth("booking-client", "booking-test");
    return headers;
  }

  private Appointment save(
      Patient patient, Specialty specialty, Doctor doctor, Room room, Instant startAt) {
    return appointments.saveAndFlush(
        new Appointment(patient, specialty, doctor, room, startAt, clock.instant()));
  }

  private Specialty newSpecialty() {
    return specialties.save(new Specialty("SPEC_" + UUID.randomUUID(), "Test specialty"));
  }

  private Doctor newDoctor(Specialty specialty) {
    return doctors.save(new Doctor("Dr. Test", "doctor@example.com", specialty));
  }

  private Room newRoom() {
    return rooms.save(new Room("Room " + UUID.randomUUID()));
  }

  private Patient newPatient() {
    UUID id = UUID.randomUUID();
    return patients.save(
        new Patient(id, "Ana Souza", id + "@example.com", "+351912345678", clock.instant()));
  }
}
