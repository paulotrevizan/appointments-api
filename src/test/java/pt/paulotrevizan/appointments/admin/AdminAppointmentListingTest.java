package pt.paulotrevizan.appointments.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import pt.paulotrevizan.appointments.booking.BookingFixtures;
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
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTask;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskType;
import pt.paulotrevizan.appointments.postbooking.persistence.OutboxTaskRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdminAppointmentListingTest {

  private static final String URI = "/api/v1/admin/appointments";
  private static final Instant NINE = Instant.parse("2035-03-05T09:00:00Z");

  private final MockMvcTester mvc;
  private final JdbcClient jdbc;
  private final Clock clock;
  private final BookingFixtures fixtures;
  private final DoctorRepository doctors;
  private final RoomRepository rooms;
  private final AppointmentRepository appointments;
  private final OutboxTaskRepository outboxTasks;
  private final Statistics statistics;
  private Specialty specialty;

  AdminAppointmentListingTest(
      @Autowired MockMvcTester mvc,
      @Autowired JdbcClient jdbc,
      @Autowired Clock clock,
      @Autowired PatientRepository patients,
      @Autowired SpecialtyRepository specialties,
      @Autowired DoctorRepository doctors,
      @Autowired RoomRepository rooms,
      @Autowired AppointmentRepository appointments,
      @Autowired OutboxTaskRepository outboxTasks,
      @Autowired EntityManagerFactory entityManagerFactory) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.clock = clock;
    this.fixtures = new BookingFixtures(patients, specialties, doctors, rooms, clock);
    this.doctors = doctors;
    this.rooms = rooms;
    this.appointments = appointments;
    this.outboxTasks = outboxTasks;
    this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
  }

  @BeforeEach
  void emptyDatabase() {
    jdbc.sql("TRUNCATE outbox_task, appointment, doctor, room, patient, specialty CASCADE")
        .update();
    specialty = fixtures.specialty();
  }

  @Test
  @DisplayName(
      "D-016: walking all pages returns every appointment once, in (startAt, id) order,"
          + " also when many share the same startAt")
  void givenManyAppointmentsSharingStartAt_whenWalkingAllPages_thenEachOnceInOrder() {
    List<Appointment> created = new ArrayList<>();
    created.add(appointmentAt(NINE.plusSeconds(3_600)));
    IntStream.range(0, 7).forEach(i -> created.add(appointmentAt(NINE)));
    created.add(appointmentAt(NINE.minusSeconds(1_800)));
    created.add(appointmentAt(NINE.plusSeconds(1_800)));

    assertThat(walkAllPages(3)).containsExactlyElementsOf(idsInListingOrder(created));
  }

  @Test
  @DisplayName("D-016: an appointment inserted before the cursor causes no duplicates or skips")
  void givenPagingInProgress_whenAppointmentInsertedBeforeCursor_thenNoDuplicatesOrSkips() {
    List<Appointment> created =
        new ArrayList<>(IntStream.range(0, 8).mapToObj(i -> appointmentAt(NINE)).toList());
    created.add(appointmentAt(NINE.plusSeconds(1_800)));
    Page first = page("?limit=3");

    appointmentAt(NINE.minusSeconds(1_800));

    List<String> walked = new ArrayList<>(first.ids());
    for (String cursor = first.nextCursor(); cursor != null; ) {
      Page next = page("?limit=3&cursor=" + cursor);
      walked.addAll(next.ids());
      cursor = next.nextCursor();
    }
    assertThat(walked).containsExactlyElementsOf(idsInListingOrder(created));
  }

  @Test
  @DisplayName("The last page has nextCursor null, also when the total is a multiple of limit")
  void givenTotalMultipleOfLimit_whenReadingSecondPage_thenNextCursorIsNull() {
    IntStream.range(0, 4).forEach(i -> appointmentAt(NINE.plusSeconds(1_800L * i)));

    Page first = page("?limit=2");
    Page last = page("?limit=2&cursor=" + first.nextCursor());

    assertThat(first.ids()).hasSize(2);
    assertThat(first.nextCursor()).isNotNull();
    assertThat(last.ids()).hasSize(2);
    assertThat(last.nextCursor()).isNull();
  }

  @Test
  @DisplayName("No appointments: items is empty and nextCursor is null")
  void givenNoAppointments_whenListing_thenEmptyItemsAndNullCursor() {
    MvcTestResult result = list("", admin());

    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result).bodyJson().isStrictlyEqualTo("{\"items\":[],\"nextCursor\":null}");
  }

  @Test
  @DisplayName("An item carries doctor, room, patient (no phone) and each post-booking status")
  void givenAppointmentWithTasksInDifferentStates_whenListing_thenItemShowsEachStatus() {
    Appointment appointment = appointmentAt(NINE);
    setTaskStatus(appointment, OutboxTaskType.DOCTOR_CALENDAR, "DONE");
    setTaskStatus(appointment, OutboxTaskType.ROOM_RESERVATION, "FAILED");

    MvcTestResult result = list("", admin());

    assertThat(result).hasStatus(HttpStatus.OK);
    assertThat(result)
        .bodyJson()
        .isStrictlyEqualTo(
            """
            {
              "items": [{
                "id": "%s",
                "status": "CONFIRMED",
                "specialty": "%s",
                "startAt": "2035-03-05T09:00:00Z",
                "endAt": "2035-03-05T09:30:00Z",
                "doctor": {"id": "%s", "name": "Dr. Test"},
                "room": {"id": "%s", "name": "%s"},
                "patient": {"id": "%s", "name": "Ana Souza", "email": "%s"},
                "postBooking": {
                  "doctorCalendar": "DONE", "roomReservation": "FAILED", "email": "PENDING"
                }
              }],
              "nextCursor": null
            }
            """
                .formatted(
                    appointment.getId(),
                    specialty.getCode(),
                    appointment.getDoctor().getId(),
                    appointment.getRoom().getId(),
                    appointment.getRoom().getName(),
                    appointment.getPatient().getId(),
                    appointment.getPatient().getEmail()));
  }

  @Test
  @DisplayName("No N+1: the number of SQL statements per page does not depend on page size")
  void givenManyAppointments_whenListingSmallAndLargePages_thenSameStatementCount() {
    IntStream.range(0, 12).forEach(i -> appointmentAt(NINE));

    long smallPage = statementsFor("?limit=2");
    long largePage = statementsFor("?limit=12");

    assertThat(smallPage).isEqualTo(largePage).isEqualTo(2);
  }

  @ParameterizedTest(name = "[{index}] cursor={0}")
  @ValueSource(strings = {"not-a-cursor", "e30", "eyJzdGFydEF0IjoieCIsImlkIjoieSJ9"})
  @DisplayName("A malformed cursor is rejected with INVALID_CURSOR")
  void givenMalformedCursor_whenListing_thenInvalidCursor(String cursor) {
    assertProblem(list("?cursor=" + cursor, admin()), "INVALID_CURSOR");
  }

  @ParameterizedTest(name = "[{index}] limit={0}")
  @ValueSource(strings = {"0", "101", "-1", "abc"})
  @DisplayName("A limit outside 1-100 is rejected with VALIDATION_ERROR")
  void givenLimitOutOfRange_whenListing_thenValidationError(String limit) {
    assertProblem(list("?limit=" + limit, admin()), "VALIDATION_ERROR");
  }

  @Test
  @DisplayName("A request without credentials gets 401")
  void givenNoCredentials_whenListing_thenUnauthorized() {
    MvcTestResult result = list("", new HttpHeaders());

    assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
    assertThat(result).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
  }

  @Test
  @DisplayName("D-012: BOOKING_CLIENT credentials cannot list appointments (403)")
  void givenBookingClientCredentials_whenListing_thenForbidden() {
    HttpHeaders bookingClient = new HttpHeaders();
    bookingClient.setBasicAuth("booking-client", "booking-test");

    MvcTestResult result = list("", bookingClient);

    assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
    assertThat(result).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
  }

  record Page(List<String> ids, String nextCursor) {}

  private List<String> walkAllPages(int limit) {
    List<String> ids = new ArrayList<>();
    String cursor = null;
    do {
      Page page = page("?limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor));
      assertThat(page.ids()).hasSizeLessThanOrEqualTo(limit);
      ids.addAll(page.ids());
      cursor = page.nextCursor();
    } while (cursor != null);
    return ids;
  }

  private Page page(String query) {
    MvcTestResult result = list(query, admin());
    assertThat(result).hasStatus(HttpStatus.OK);
    DocumentContext body =
        JsonPath.parse(
            new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    return new Page(body.read("$.items[*].id"), body.read("$.nextCursor"));
  }

  private long statementsFor(String query) {
    statistics.clear();
    page(query);
    return statistics.getPrepareStatementCount();
  }

  private MvcTestResult list(String query, HttpHeaders auth) {
    return mvc.get().uri(URI + query).headers(auth).exchange();
  }

  private void assertProblem(MvcTestResult result, String code) {
    assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(result).contentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
    assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo(URI);
  }

  // PostgreSQL orders uuid as unsigned bytes, like the lowercase hex text (java.util.UUID does
  // not).
  private static List<String> idsInListingOrder(List<Appointment> appointments) {
    return appointments.stream()
        .sorted(
            Comparator.comparing(Appointment::getStartAt)
                .thenComparing(appointment -> appointment.getId().toString()))
        .map(appointment -> appointment.getId().toString())
        .toList();
  }

  private Appointment appointmentAt(Instant startAt) {
    Doctor doctor = doctors.save(new Doctor("Dr. Test", "doctor@example.com", specialty));
    Room room = rooms.save(new Room("Room " + UUID.randomUUID()));
    Patient patient = fixtures.patient();
    Appointment appointment =
        appointments.save(
            new Appointment(patient, specialty, doctor, room, startAt, clock.instant()));
    outboxTasks.saveAll(
        Arrays.stream(OutboxTaskType.values())
            .map(type -> new OutboxTask(appointment, type))
            .toList());
    return appointment;
  }

  private void setTaskStatus(Appointment appointment, OutboxTaskType type, String status) {
    jdbc.sql(
            """
            UPDATE outbox_task SET status = :status
            WHERE appointment_id = :appointmentId AND type = :type
            """)
        .param("status", status)
        .param("appointmentId", appointment.getId())
        .param("type", type.name())
        .update();
  }

  private static HttpHeaders admin() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBasicAuth("admin", "admin-test");
    return headers;
  }
}
