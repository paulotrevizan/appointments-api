package pt.paulotrevizan.appointments.postbooking.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
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
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTask;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskType;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OutboxClaimTest {

  private static final double LEASE_SECONDS = 30;

  private final OutboxTaskRepository tasks;
  private final AppointmentRepository appointments;
  private final PatientRepository patients;
  private final SpecialtyRepository specialties;
  private final DoctorRepository doctors;
  private final RoomRepository rooms;
  private final JdbcClient jdbc;
  private final Clock clock;

  OutboxClaimTest(
      @Autowired OutboxTaskRepository tasks,
      @Autowired AppointmentRepository appointments,
      @Autowired PatientRepository patients,
      @Autowired SpecialtyRepository specialties,
      @Autowired DoctorRepository doctors,
      @Autowired RoomRepository rooms,
      @Autowired JdbcClient jdbc,
      @Autowired Clock clock) {
    this.tasks = tasks;
    this.appointments = appointments;
    this.patients = patients;
    this.specialties = specialties;
    this.doctors = doctors;
    this.rooms = rooms;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @BeforeEach
  void emptyDatabase() {
    jdbc.sql("TRUNCATE outbox_task, appointment, doctor, room, patient, specialty CASCADE")
        .update();
  }

  @Test
  @DisplayName("D-004: concurrent claims never return the same task")
  void givenDueTasks_whenClaimedConcurrently_thenNoTaskIsClaimedTwice() throws Exception {
    createAppointmentsWithTasks(20);
    int workers = 8;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<List<OutboxTask>>> claims = new ArrayList<>();

    try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
      for (int i = 0; i < workers; i++) {
        claims.add(
            executor.submit(
                () -> {
                  start.await();
                  return tasks.claimDue(10, LEASE_SECONDS);
                }));
      }
      start.countDown();
    }

    List<UUID> claimed = new ArrayList<>();
    for (Future<List<OutboxTask>> claim : claims) {
      claim.get().forEach(task -> claimed.add(task.getId()));
    }
    assertThat(claimed).isNotEmpty().doesNotHaveDuplicates().hasSizeLessThanOrEqualTo(60);
  }

  @Test
  @DisplayName("A claimed task gets a lease from the database clock and is not claimed again")
  void givenClaimedTask_whenLeaseStillValid_thenNotClaimedAgain() {
    createAppointmentsWithTasks(1);

    List<OutboxTask> first = tasks.claimDue(10, LEASE_SECONDS);

    assertThat(first).hasSize(3);
    Instant databaseNow = jdbc.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
    assertThat(first)
        .allSatisfy(
            task ->
                assertThat(task.getLockedUntil())
                    .isAfter(databaseNow.plus(Duration.ofSeconds(25))));
    assertThat(tasks.claimDue(10, LEASE_SECONDS)).isEmpty();
  }

  @Test
  @DisplayName("HLD 6.2: a task whose lease expired (crashed worker) is claimed again")
  void givenClaimedTask_whenLeaseExpired_thenClaimedAgain() {
    createAppointmentsWithTasks(1);
    List<UUID> firstClaim =
        tasks.claimDue(10, LEASE_SECONDS).stream().map(OutboxTask::getId).toList();

    jdbc.sql("UPDATE outbox_task SET locked_until = now() - interval '1 second'").update();

    assertThat(tasks.claimDue(10, LEASE_SECONDS))
        .extracting(OutboxTask::getId)
        .containsExactlyInAnyOrderElementsOf(firstClaim);
  }

  @Test
  @DisplayName("A task scheduled for later is not claimed before its time")
  void givenTaskScheduledInTheFuture_whenClaiming_thenNotClaimed() {
    createAppointmentsWithTasks(1);
    jdbc.sql("UPDATE outbox_task SET next_attempt_at = now() + interval '1 minute'").update();

    assertThat(tasks.claimDue(10, LEASE_SECONDS)).isEmpty();
  }

  @Test
  @DisplayName("An outcome recorded with an expired lease changes nothing")
  void givenLeaseTakenOverByAnotherClaim_whenOldClaimRecordsOutcome_thenIgnored() {
    createAppointmentsWithTasks(1);
    OutboxTask stale = tasks.claimDue(1, LEASE_SECONDS).getFirst();
    // Only the stale task can be claimed again, once its lease has expired.
    jdbc.sql(
            """
            UPDATE outbox_task
            SET status = CASE WHEN id = :id THEN status ELSE 'DONE' END,
                locked_until = now() - interval '1 second'
            """)
        .param("id", stale.getId())
        .update();
    OutboxTask current = tasks.claimDue(1, LEASE_SECONDS).getFirst();

    assertThat(current.getId()).isEqualTo(stale.getId());
    assertThat(tasks.markFailed(stale.getId(), stale.getLockedUntil(), "late")).isZero();
    assertThat(tasks.markDone(current.getId(), current.getLockedUntil())).isOne();
    assertThat(
            jdbc.sql("SELECT status FROM outbox_task WHERE id = :id")
                .param("id", stale.getId())
                .query(String.class)
                .single())
        .isEqualTo("DONE");
  }

  private void createAppointmentsWithTasks(int count) {
    Specialty specialty =
        specialties.save(new Specialty("SPEC_" + UUID.randomUUID(), "Test specialty"));
    Doctor doctor = doctors.save(new Doctor("Dr. Test", "doctor@example.com", specialty));
    Room room = rooms.save(new Room("Room " + UUID.randomUUID()));
    UUID patientId = UUID.randomUUID();
    Patient patient =
        patients.save(
            new Patient(
                patientId,
                "Patient",
                patientId + "@example.com",
                "+351910000000",
                clock.instant()));
    Instant firstSlot = Instant.parse("2035-03-05T08:00:00Z");
    IntStream.range(0, count)
        .forEach(
            i -> {
              Appointment appointment =
                  appointments.save(
                      new Appointment(
                          patient,
                          specialty,
                          doctor,
                          room,
                          firstSlot.plus(Appointment.SLOT_DURATION.multipliedBy(i)),
                          clock.instant()));
              tasks.saveAll(
                  Arrays.stream(OutboxTaskType.values())
                      .map(type -> new OutboxTask(appointment, type))
                      .toList());
            });
  }
}
