package pt.paulotrevizan.appointments.postbooking.application;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTask;
import pt.paulotrevizan.appointments.postbooking.persistence.OutboxTaskRepository;

/**
 * HLD 6.2: claims due tasks with a lease, then calls the external systems outside any database
 * transaction. Delivery is at least once; the appointment id makes repeated calls harmless (A7).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(name = "appointments.outbox.enabled", matchIfMissing = true)
class OutboxWorker {

  private static final int MAX_ERROR_LENGTH = 2000;

  private final OutboxTaskRepository tasks;
  private final OutboxProperties properties;
  private final RetryPolicy retryPolicy;
  private final DoctorCalendarTaskHandler calendarHandler;
  private final RoomReservationTaskHandler roomHandler;
  private final EmailTaskHandler emailHandler;

  @Scheduled(fixedDelayString = "${appointments.outbox.poll-interval}")
  void poll() {
    List<OutboxTask> claimed = tasks.claimDue(properties.batchSize(), seconds(properties.lease()));
    // One virtual thread per task: a slow system cannot hold the rest of the batch past its lease.
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      claimed.forEach(task -> executor.execute(() -> processSafely(task)));
    }
  }

  private void processSafely(OutboxTask task) {
    try {
      process(task);
    } catch (RuntimeException e) {
      log.error(
          "Could not record the outcome of outbox task {}; it runs again when its lease expires",
          task.getId(),
          e);
    }
  }

  private void process(OutboxTask task) {
    try {
      dispatch(task);
    } catch (Exception e) {
      recordFailure(task, e);
      return;
    }
    checkLease(task, tasks.markDone(task.getId(), task.getLockedUntil()));
    log.info("Outbox task {} ({}) done", task.getId(), task.getType());
  }

  private void dispatch(OutboxTask task) {
    switch (task.getType()) {
      case DOCTOR_CALENDAR -> calendarHandler.handle(task.getAppointment().getId());
      case ROOM_RESERVATION -> roomHandler.handle(task.getAppointment().getId());
      case EMAIL -> emailHandler.handle(task.getAppointment().getId());
    }
  }

  private void recordFailure(OutboxTask task, Exception error) {
    int attemptsMade = task.getAttempts() + 1;
    String lastError = truncate(error.toString());
    Optional<Duration> delay = retryPolicy.retryDelay(attemptsMade, error);
    if (delay.isPresent()) {
      checkLease(
          task,
          tasks.scheduleRetry(
              task.getId(), task.getLockedUntil(), seconds(delay.get()), lastError));
      log.warn(
          "Outbox task {} ({}) attempt {} failed with {}; retrying in {}",
          task.getId(),
          task.getType(),
          attemptsMade,
          summary(error),
          delay.get());
    } else {
      checkLease(task, tasks.markFailed(task.getId(), task.getLockedUntil(), lastError));
      log.error(
          "Outbox task {} ({}) FAILED after {} attempts with {}",
          task.getId(),
          task.getType(),
          attemptsMade,
          summary(error));
    }
  }

  // The lease is the fencing token: once it expires, another instance may own the task.
  private static void checkLease(OutboxTask task, int updatedRows) {
    if (updatedRows == 0) {
      log.warn("Outbox task {} lost its lease before its outcome was recorded", task.getId());
    }
  }

  // Logs carry no response bodies, which may echo patient data; last_error keeps the full error.
  private static String summary(Exception error) {
    return error instanceof RestClientResponseException response
        ? "HTTP " + response.getStatusCode().value()
        : error.getClass().getSimpleName();
  }

  private static String truncate(String text) {
    return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
  }

  private static double seconds(Duration duration) {
    return duration.toMillis() / 1000.0;
  }
}
