package pt.paulotrevizan.appointments.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import pt.paulotrevizan.appointments.TestcontainersConfiguration;
import pt.paulotrevizan.appointments.catalog.domain.Patient;
import pt.paulotrevizan.appointments.catalog.domain.Specialty;
import pt.paulotrevizan.appointments.catalog.persistence.DoctorRepository;
import pt.paulotrevizan.appointments.catalog.persistence.PatientRepository;
import pt.paulotrevizan.appointments.catalog.persistence.RoomRepository;
import pt.paulotrevizan.appointments.catalog.persistence.SpecialtyRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ConcurrentBookingTest {

  private static final Instant START = Instant.parse("2035-03-05T14:00:00Z");

  private record Response(int status, String body) {}

  private final MockMvcTester mvc;
  private final JdbcClient jdbc;
  private final BookingFixtures fixtures;

  ConcurrentBookingTest(
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

  // With 3 attempts per request (D-003) the outcome is exact while min(doctors, rooms) <= 3.
  @ParameterizedTest(name = "{0} doctors, {1} rooms")
  @CsvSource({"2, 3", "3, 2", "3, 3"})
  @DisplayName("I1, I2: 20 parallel requests for one slot get exactly min(doctors, rooms) bookings")
  void givenFewerDoctorsOrRoomsThanRequests_whenBookedInParallel_thenExactlyMinSucceed(
      int doctors, int rooms) {
    Specialty specialty = fixtures.specialty();
    fixtures.doctors(specialty, doctors);
    fixtures.rooms(rooms);
    List<Callable<Response>> requests =
        IntStream.range(0, 20).mapToObj(i -> booking(fixtures.patient(), specialty)).toList();

    List<Response> responses = inParallel(requests);

    int expected = Math.min(doctors, rooms);
    assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(expected);
    assertThat(responses)
        .filteredOn(r -> r.status() != 201)
        .hasSize(20 - expected)
        .allSatisfy(
            r -> {
              assertThat(r.status()).isEqualTo(409);
              assertThat(r.body()).containsPattern("NO_(DOCTOR|ROOM)_AVAILABLE");
            });
    assertThat(count("appointment")).isEqualTo(expected);
    assertThat(doubleBookings()).isZero();
  }

  @Test
  @DisplayName("I5: 10 parallel identical requests create one appointment and all get it back")
  void givenOnePatient_whenSameRequestSentInParallel_thenOneAppointmentForAll() {
    Specialty specialty = fixtures.specialty();
    fixtures.doctors(specialty, 3);
    fixtures.rooms(3);
    Patient patient = fixtures.patient();
    List<Callable<Response>> requests =
        IntStream.range(0, 10).mapToObj(i -> booking(patient, specialty)).toList();

    List<Response> responses = inParallel(requests);

    assertThat(responses).extracting(Response::status).containsOnly(201);
    assertThat(responses).extracting(Response::body).containsOnly(responses.getFirst().body());
    assertThat(count("appointment")).isEqualTo(1);
    assertThat(count("outbox_task")).isEqualTo(3);
  }

  private Callable<Response> booking(Patient patient, Specialty specialty) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBasicAuth("booking-client", "booking-test");
    String body =
        "{\"patientId\":\"%s\",\"specialty\":\"%s\",\"startAt\":\"%s\"}"
            .formatted(patient.getId(), specialty.getCode(), START);
    return () -> {
      MvcTestResult result =
          mvc.post()
              .uri("/api/v1/appointments")
              .headers(headers)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body)
              .exchange();
      return new Response(
          result.getResponse().getStatus(), result.getResponse().getContentAsString());
    };
  }

  /** Starts all requests at the same time and waits for every response. */
  private static List<Response> inParallel(List<Callable<Response>> requests) {
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Response>> futures =
          requests.stream()
              .map(
                  request ->
                      executor.submit(
                          () -> {
                            start.await();
                            return request.call();
                          }))
              .toList();
      start.countDown();
      return futures.stream().map(ConcurrentBookingTest::await).toList();
    }
  }

  private static Response await(Future<Response> future) {
    try {
      return future.get();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private int count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
  }

  private int doubleBookings() {
    return jdbc.sql(
            """
            SELECT count(*) FROM appointment a JOIN appointment b
              ON a.id < b.id AND a.start_at = b.start_at
             AND (a.doctor_id = b.doctor_id OR a.room_id = b.room_id OR a.patient_id = b.patient_id)
            """)
        .query(Integer.class)
        .single();
  }
}
