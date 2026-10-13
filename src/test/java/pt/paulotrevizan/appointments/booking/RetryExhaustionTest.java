package pt.paulotrevizan.appointments.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import pt.paulotrevizan.appointments.catalog.domain.Patient;
import pt.paulotrevizan.appointments.catalog.domain.Specialty;
import pt.paulotrevizan.appointments.catalog.persistence.DoctorRepository;
import pt.paulotrevizan.appointments.catalog.persistence.PatientRepository;
import pt.paulotrevizan.appointments.catalog.persistence.RoomRepository;
import pt.paulotrevizan.appointments.catalog.persistence.SpecialtyRepository;

@SpringBootTest(properties = "appointments.booking.max-retries=0")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RetryExhaustionTest {

  private static final Instant START = Instant.parse("2035-03-05T14:00:00Z");

  private final MockMvcTester mvc;
  private final JdbcClient jdbc;
  private final BookingFixtures fixtures;

  RetryExhaustionTest(
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
  @DisplayName("D-003: a request that runs out of attempts gets a 409, never a server error")
  void givenNoRetries_whenRequestsRaceForOneSlot_thenEveryLoserGetsConflict() throws Exception {
    Specialty specialty = fixtures.specialty();
    fixtures.doctors(specialty, 2);
    fixtures.rooms(2);
    List<Patient> patients = IntStream.range(0, 10).mapToObj(i -> fixtures.patient()).toList();

    List<Integer> statuses = bookInParallel(patients, specialty);

    assertThat(statuses).containsOnly(201, 409).contains(201);
    assertThat(jdbc.sql("SELECT count(*) FROM appointment").query(Integer.class).single())
        .isEqualTo((int) statuses.stream().filter(status -> status == 201).count());
  }

  private List<Integer> bookInParallel(List<Patient> patients, Specialty specialty)
      throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setBasicAuth("booking-client", "booking-test");
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Integer>> futures =
          patients.stream()
              .map(
                  patient ->
                      executor.submit(
                          () -> {
                            start.await();
                            return mvc.post()
                                .uri("/api/v1/appointments")
                                .headers(headers)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                    "{\"patientId\":\"%s\",\"specialty\":\"%s\",\"startAt\":\"%s\"}"
                                        .formatted(patient.getId(), specialty.getCode(), START))
                                .exchange()
                                .getResponse()
                                .getStatus();
                          }))
              .toList();
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> future : futures) {
        statuses.add(future.get());
      }
      return statuses;
    }
  }
}
