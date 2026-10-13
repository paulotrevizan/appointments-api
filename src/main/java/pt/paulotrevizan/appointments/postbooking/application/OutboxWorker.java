package pt.paulotrevizan.appointments.postbooking.application;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pt.paulotrevizan.appointments.integration.IntegrationException;
import pt.paulotrevizan.appointments.postbooking.persistence.ClaimedTask;
import pt.paulotrevizan.appointments.postbooking.persistence.OutboxTaskQueue;

/**
 * D-004: claims due tasks and runs them in parallel on virtual threads, one per task. No
 * transaction is open during the external calls; a crashed instance's tasks are claimed again when
 * their lease expires.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("appointments.outbox.worker.enabled")
@RequiredArgsConstructor
class OutboxWorker {

  private static final ThreadFactory TASK_THREADS =
      Thread.ofVirtual()
          .name("outbox-task-", 0)
          .uncaughtExceptionHandler(
              (thread, error) -> log.error("Outbox task thread {} failed", thread.getName(), error))
          .factory();

  private final OutboxTaskQueue queue;
  private final PostBookingDispatcher dispatcher;
  private final RetryPolicy retryPolicy;
  private final OutboxProperties properties;

  @Scheduled(fixedDelayString = "${appointments.outbox.poll-interval}")
  void processDueTasks() {
    List<ClaimedTask> tasks =
        queue.claimDue(dispatcher.handledTypes(), properties.batchSize(), properties.lease());
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(TASK_THREADS)) {
      tasks.forEach(task -> executor.execute(() -> process(task)));
    }
  }

  private void process(ClaimedTask task) {
    try {
      dispatcher.dispatch(task);
    } catch (IntegrationException e) {
      recordFailure(task, e);
      return;
    }
    queue.markDone(task.id());
    log.info(
        "Outbox task {} ({}) for appointment {} done",
        task.id(),
        task.type(),
        task.appointmentId());
  }

  private void recordFailure(ClaimedTask task, IntegrationException failure) {
    int failedAttempts = task.attempts() + 1;
    if (retryPolicy.canRetry(failure, failedAttempts)) {
      Duration delay = retryPolicy.delayAfter(failedAttempts);
      queue.scheduleRetry(task.id(), delay, failure.getMessage());
      log.warn(
          "Outbox task {} ({}) attempt {} failed, next try in {}: {}",
          task.id(),
          task.type(),
          failedAttempts,
          delay,
          failure.getMessage());
    } else {
      queue.markFailed(task.id(), failure.getMessage());
      log.error(
          "Outbox task {} ({}) for appointment {} FAILED after {} attempts: {}",
          task.id(),
          task.type(),
          task.appointmentId(),
          failedAttempts,
          failure.getMessage());
    }
  }
}
