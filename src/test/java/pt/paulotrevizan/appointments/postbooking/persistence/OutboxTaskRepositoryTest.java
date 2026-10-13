package pt.paulotrevizan.appointments.postbooking.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import pt.paulotrevizan.appointments.TestcontainersConfiguration;
import pt.paulotrevizan.appointments.booking.persistence.AppointmentRepository;
import pt.paulotrevizan.appointments.catalog.persistence.DoctorRepository;
import pt.paulotrevizan.appointments.catalog.persistence.PatientRepository;
import pt.paulotrevizan.appointments.catalog.persistence.RoomRepository;
import pt.paulotrevizan.appointments.catalog.persistence.SpecialtyRepository;
import pt.paulotrevizan.appointments.postbooking.PostBookingFixtures;
import pt.paulotrevizan.appointments.postbooking.application.PostBookingTasks;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTask;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OutboxTaskRepositoryTest {

  private static final Instant START = Instant.parse("2035-03-05T14:00:00Z");
  private static final List<String> CALENDAR_AND_ROOM =
      List.of("DOCTOR_CALENDAR", "ROOM_RESERVATION");

  private final OutboxTaskRepository tasks;
  private final TransactionTemplate transactions;
  private final JdbcClient jdbc;
  private final Clock clock;
  private final PostBookingFixtures fixtures;

  OutboxTaskRepositoryTest(
      @Autowired OutboxTaskRepository tasks,
      @Autowired TransactionTemplate transactions,
      @Autowired JdbcClient jdbc,
      @Autowired Clock clock,
      @Autowired PatientRepository patients,
      @Autowired SpecialtyRepository specialties,
      @Autowired DoctorRepository doctors,
      @Autowired RoomRepository rooms,
      @Autowired AppointmentRepository appointments,
      @Autowired PostBookingTasks postBookingTasks) {
    this.tasks = tasks;
    this.transactions = transactions;
    this.jdbc = jdbc;
    this.clock = clock;
    this.fixtures =
        new PostBookingFixtures(
            patients, specialties, doctors, rooms, appointments, postBookingTasks, clock);
  }

  @BeforeEach
  void emptyDatabase() {
    jdbc.sql("TRUNCATE outbox_task, appointment, doctor, room, patient, specialty CASCADE")
        .update();
  }

  @Test
  @DisplayName("Due calendar and room tasks are returned; EMAIL is not picked up before Step 5")
  void givenBookedAppointment_whenDueTasksLocked_thenCalendarAndRoomOnly() {
    fixtures.bookedAppointment(START);

    assertThat(lockDue(20))
        .extracting(task -> task.getType().name())
        .containsExactlyInAnyOrderElementsOf(CALENDAR_AND_ROOM);
  }

  @Test
  @DisplayName("D-004: two workers running at the same time never get the same task")
  void givenTasksLockedByOneWorker_whenAnotherLooksForDueTasks_thenItSkipsThem() {
    IntStream.range(0, 5).forEach(i -> fixtures.bookedAppointment(START));
    CountDownLatch firstHoldsItsLocks = new CountDownLatch(1);
    CountDownLatch secondIsDone = new CountDownLatch(1);

    CompletableFuture<List<UUID>> first =
        CompletableFuture.supplyAsync(
            () ->
                transactions.execute(
                    status -> {
                      List<UUID> locked = ids(dueForUpdate(4));
                      firstHoldsItsLocks.countDown();
                      await(secondIsDone);
                      return locked;
                    }));
    await(firstHoldsItsLocks);
    List<UUID> second = transactions.execute(status -> ids(dueForUpdate(20)));
    secondIsDone.countDown();

    assertThat(first.join()).hasSize(4).doesNotContainAnyElementsOf(second);
    assertThat(second).hasSize(6);
  }

  @Test
  @DisplayName("D-004: tasks locked by a worker that dies are picked up again")
  void givenWorkerTransactionRolledBack_whenDueTasksLocked_thenSameTasksReturned() {
    fixtures.bookedAppointment(START);
    List<UUID> lockedByDeadWorker =
        transactions.execute(
            status -> {
              List<UUID> locked = ids(dueForUpdate(20));
              status.setRollbackOnly();
              return locked;
            });

    assertThat(ids(lockDue(20))).containsExactlyInAnyOrderElementsOf(lockedByDeadWorker);
  }

  @Test
  @DisplayName("Tasks that are done or scheduled for later are not due")
  void givenDoneAndRescheduledTasks_whenDueTasksLocked_thenNone() {
    fixtures.bookedAppointment(START);
    transactions.executeWithoutResult(
        status -> {
          List<OutboxTask> due = dueForUpdate(20);
          Instant now = clock.instant();
          due.get(0).markDone(now);
          due.get(1).recordFailure("HTTP 503", now, now.plusSeconds(60));
        });

    assertThat(lockDue(20)).isEmpty();
  }

  private List<OutboxTask> lockDue(int limit) {
    return transactions.execute(status -> dueForUpdate(limit));
  }

  private List<OutboxTask> dueForUpdate(int limit) {
    return tasks.findDueForUpdate(CALENDAR_AND_ROOM, clock.instant(), limit);
  }

  private static List<UUID> ids(List<OutboxTask> tasks) {
    return tasks.stream().map(OutboxTask::getId).toList();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
