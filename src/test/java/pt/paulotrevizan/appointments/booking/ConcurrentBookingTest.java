package pt.paulotrevizan.appointments.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import pt.paulotrevizan.appointments.TestcontainersConfiguration;

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

  @ParameterizedTest(name = "{0} doctors, {1} rooms")
  @CsvSource({"2, 3", "3, 2", "3, 3"})
  @DisplayName("I1, I2: 20 parallel requests for one slot get exactly min(doctors, rooms) bookings")
  void givenFewerDoctorsOrRoomsThanRequests_whenBookedInParallel_thenExactlyMinSucceed(
      int doctors, int rooms) {
    Specialty specialty = fixtures.specialty();
    fixtures.doctors(specialty, doctors);
    fixtures.rooms(rooms);
    List<Patient> patients = IntStream.range(0, 20).mapToObj(i -> fixtures.patient()).toList();

    List<Response> responses =
        inParallel(patients.stream().map(patient -> bookingOf(patient, specialty, START)).toList());

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
    assertThat(overlappingPairs()).isZero();
  }

  @Test
  @DisplayName("I5: 10 parallel identical requests from one patient create exactly one appointment")
  void givenOnePatient_whenSameRequestSentInParallel_thenOneAppointmentAndNoErrors() {
    Specialty specialty = fixtures.specialty();
    fixtures.doctors(specialty, 3);
    fixtures.rooms(3);
    Patient patient = fixtures.patient();

    List<Response> responses =
        inParallel(
            IntStream.range(0, 10).mapToObj(i -> bookingOf(patient, specialty, START)).toList());

    assertThat(responses).extracting(Response::status).containsOnly(200, 201);
    assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
    assertThat(responses.stream().map(r -> r.body()).distinct()).hasSize(1);
    assertThat(count("appointment")).isEqualTo(1);
    assertThat(count("outbox_task")).isEqualTo(3);
  }

  @Test
  @DisplayName(
      "Parallel requests for different slots do not block each other, even with one doctor")
  void givenOneDoctorAndRoom_whenDifferentSlotsBookedInParallel_thenAllSucceed() {
    Specialty specialty = fixtures.specialty();
    fixtures.doctors(specialty, 1);
    fixtures.rooms(1);

    List<Response> responses =
        inParallel(
            IntStream.range(0, 10)
                .mapToObj(
                    i ->
                        bookingOf(
                            fixtures.patient(), specialty, START.plus(Duration.ofMinutes(30L * i))))
                .toList());

    assertThat(responses).extracting(Response::status).containsOnly(201);
    assertThat(count("appointment")).isEqualTo(10);
  }

  private Callable<Response> bookingOf(Patient patient, Specialty specialty, Instant startAt) {
    return () -> {
      HttpHeaders headers = new HttpHeaders();
      headers.setBasicAuth("booking-client", "booking-test");
      String body =
          "{\"patientId\":\"%s\",\"specialty\":\"%s\",\"startAt\":\"%s\"}"
              .formatted(patient.getId(), specialty.getCode(), startAt);
      var result =
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

  private List<Response> inParallel(List<Callable<Response>> requests) {
    ExecutorService executor = Executors.newFixedThreadPool(requests.size());
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Response>> futures = new ArrayList<>();
      for (Callable<Response> request : requests) {
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return request.call();
                }));
      }
      start.countDown();
      List<Response> responses = new ArrayList<>();
      for (Future<Response> future : futures) {
        responses.add(future.get());
      }
      return responses;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    } finally {
      executor.shutdownNow();
    }
  }

  private int count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
  }

  private int overlappingPairs() {
    return jdbc.sql(
            """
            SELECT count(*) FROM appointment a JOIN appointment b
              ON a.id < b.id AND a.slot && b.slot
             AND (a.doctor_id = b.doctor_id OR a.room_id = b.room_id OR a.patient_id = b.patient_id)
            """)
        .query(Integer.class)
        .single();
  }
}
