package pt.paulotrevizan.appointments.postbooking.application;

import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pt.paulotrevizan.appointments.integration.HttpFailures;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTask;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskStatus;
import pt.paulotrevizan.appointments.postbooking.persistence.OutboxTaskRepository;

/**
 * D-004: every poll, one transaction locks up to {@code batch-size} due tasks ({@code FOR UPDATE
 * SKIP LOCKED}, so instances never share a task), runs them and records each outcome on the task.
 * If the instance dies mid-batch the transaction rolls back and the tasks are picked up again; the
 * idempotency key makes the repeated calls harmless.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("appointments.outbox.worker.enabled")
@RequiredArgsConstructor
class OutboxWorker {

  private final OutboxTaskRepository tasks;
  private final PostBookingDispatcher dispatcher;
  private final RetryPolicy retryPolicy;
  private final OutboxProperties properties;
  private final Clock clock;

  @Scheduled(fixedDelayString = "${appointments.outbox.poll-interval}")
  @Transactional
  public void processDueTasks() {
    Instant now = clock.instant();
    for (OutboxTask task :
        tasks.findDueForUpdate(PostBookingDispatcher.HANDLED_TYPES, now, properties.batchSize())) {
      process(task, now);
    }
  }

  private void process(OutboxTask task, Instant now) {
    try {
      dispatcher.dispatch(task);
      task.markDone(now);
      log.info("Outbox task {} ({}) done", task.getId(), task.getType());
    } catch (RuntimeException e) {
      Instant nextAttemptAt =
          HttpFailures.isRetryable(e)
              ? retryPolicy.nextAttemptAt(task.getAttempts() + 1, now)
              : null;
      task.recordFailure(HttpFailures.describe(e), now, nextAttemptAt);
      if (task.getStatus() == OutboxTaskStatus.FAILED) {
        log.error(
            "Outbox task {} ({}) FAILED: {}", task.getId(), task.getType(), task.getLastError());
      } else {
        log.warn(
            "Outbox task {} ({}) failed, retry at {}: {}",
            task.getId(),
            task.getType(),
            nextAttemptAt,
            task.getLastError());
      }
    }
  }
}
