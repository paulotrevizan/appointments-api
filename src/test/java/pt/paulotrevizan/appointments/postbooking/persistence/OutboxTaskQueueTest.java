package pt.paulotrevizan.appointments.postbooking.persistence;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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
import pt.paulotrevizan.appointments.catalog.persistence.DoctorRepository;
import pt.paulotrevizan.appointments.catalog.persistence.PatientRepository;
import pt.paulotrevizan.appointments.catalog.persistence.RoomRepository;
import pt.paulotrevizan.appointments.catalog.persistence.SpecialtyRepository;
import pt.paulotrevizan.appointments.postbooking.PostBookingFixtures;
import pt.paulotrevizan.appointments.postbooking.application.PostBookingTasks;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskType;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OutboxTaskQueueTest {

  private static final Instant START = Instant.parse("2035-03-05T14:00:00Z");
  private static final Set<OutboxTaskType> CALENDAR_AND_ROOM =
      EnumSet.of(OutboxTaskType.DOCTOR_CALENDAR, OutboxTaskType.ROOM_RESERVATION);
  private static final Duration LEASE = Duration.ofSeconds(30);

  private final OutboxTaskQueue queue;
  private final JdbcClient jdbc;
  private final PostBookingFixtures fixtures;

  OutboxTaskQueueTest(
      @Autowired OutboxTaskQueue queue,
      @Autowired JdbcClient jdbc,
      @Autowired Clock clock,
      @Autowired PatientRepository patients,
      @Autowired SpecialtyRepository specialties,
      @Autowired DoctorRepository doctors,
      @Autowired RoomRepository rooms,
      @Autowired AppointmentRepository appointments,
      @Autowired PostBookingTasks postBookingTasks) {
    this.queue = queue;
    this.jdbc = jdbc;
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
  @DisplayName("A claim returns the due tasks of the requested types with their appointment data")
  void givenBookedAppointment_whenClaimed_thenCalendarAndRoomTasksWithAppointmentData() {
    Appointment appointment = fixtures.bookedAppointment(START);

    List<ClaimedTask> claimed = queue.claimDue(CALENDAR_AND_ROOM, 20, LEASE);

    assertThat(claimed)
        .extracting(ClaimedTask::type)
        .containsExactlyInAnyOrder(OutboxTaskType.DOCTOR_CALENDAR, OutboxTaskType.ROOM_RESERVATION);
    assertThat(claimed)
        .allSatisfy(
            task -> {
              assertThat(task.appointmentId()).isEqualTo(appointment.getId());
              assertThat(task.doctorId()).isEqualTo(appointment.getDoctor().getId());
              assertThat(task.roomId()).isEqualTo(appointment.getRoom().getId());
              assertThat(task.startAt()).isEqualTo(appointment.getStartAt());
              assertThat(task.endAt()).isEqualTo(appointment.getEndAt());
              assertThat(task.attempts()).isZero();
            });
  }

  @Test
  @DisplayName("D-004: two concurrent claims never return the same task")
  void givenDueTasks_whenClaimedConcurrently_thenNoTaskIsReturnedTwice() throws Exception {
    IntStream.range(0, 10).forEach(i -> fixtures.bookedAppointment(START));
    CountDownLatch start = new CountDownLatch(1);

    List<UUID> claimed = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<List<ClaimedTask>>> claims =
          IntStream.range(0, 4)
              .mapToObj(
                  i ->
                      executor.submit(
                          () -> {
                            start.await();
                            return queue.claimDue(CALENDAR_AND_ROOM, 5, LEASE);
                          }))
              .toList();
      start.countDown();
      for (Future<List<ClaimedTask>> claim : claims) {
        claim.get().forEach(task -> claimed.add(task.id()));
      }
    }

    assertThat(claimed).hasSize(20).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("D-004: a leased task is not claimed again until its lease expires")
  void givenClaimedTask_whenLeaseExpires_thenClaimedAgain() {
    fixtures.bookedAppointment(START);
    List<ClaimedTask> first = queue.claimDue(CALENDAR_AND_ROOM, 20, Duration.ofSeconds(1));

    assertThat(queue.claimDue(CALENDAR_AND_ROOM, 20, LEASE)).isEmpty();
    await()
        .atMost(5, SECONDS)
        .untilAsserted(
            () ->
                assertThat(queue.claimDue(CALENDAR_AND_ROOM, 20, LEASE))
                    .extracting(ClaimedTask::id)
                    .containsExactlyInAnyOrderElementsOf(
                        first.stream().map(ClaimedTask::id).toList()));
  }

  @Test
  @DisplayName("Done tasks and tasks scheduled for later are not claimed")
  void givenDoneAndRescheduledTasks_whenClaimed_thenNothingIsDue() {
    fixtures.bookedAppointment(START);
    List<ClaimedTask> claimed = queue.claimDue(CALENDAR_AND_ROOM, 20, LEASE);
    queue.markDone(claimed.get(0).id());
    queue.scheduleRetry(claimed.get(1).id(), Duration.ofMinutes(5), "HTTP 503");

    assertThat(queue.claimDue(CALENDAR_AND_ROOM, 20, Duration.ZERO)).isEmpty();
  }

  @Test
  @DisplayName("Only the requested task types are claimed: EMAIL stays PENDING until Step 5")
  void givenEmailTask_whenCalendarAndRoomClaimed_thenEmailIsNotClaimed() {
    fixtures.bookedAppointment(START);

    queue.claimDue(CALENDAR_AND_ROOM, 20, LEASE);

    assertThat(
            jdbc.sql("SELECT locked_until IS NULL FROM outbox_task WHERE type = 'EMAIL'")
                .query(Boolean.class)
                .single())
        .isTrue();
  }
}
