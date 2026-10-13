package pt.paulotrevizan.appointments.postbooking.application;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pt.paulotrevizan.appointments.integration.IntegrationException;
import pt.paulotrevizan.appointments.postbooking.persistence.ClaimedTask;
import pt.paulotrevizan.appointments.postbooking.persistence.OutboxTaskQueue;

/**
 * D-004: claims due tasks and runs them in parallel, one virtual thread per task. No transaction is
 * open during the external calls. A task still running when its lease ends is left behind (it may
 * be claimed again, which is harmless thanks to the idempotency key); on shutdown, running tasks
 * finish before the executor closes.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("appointments.outbox.worker.enabled")
@RequiredArgsConstructor
class OutboxWorker {

  private final ExecutorService executor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("outbox-task-", 0).factory());

  private final OutboxTaskQueue queue;
  private final PostBookingDispatcher dispatcher;
  private final OutboxRetrySchedule retrySchedule;
  private final OutboxProperties properties;

  @Scheduled(fixedDelayString = "${appointments.outbox.poll-interval}")
  void processDueTasks() {
    List<ClaimedTask> tasks =
        queue.claimDue(dispatcher.handledTypes(), properties.batchSize(), properties.lease());
    CompletableFuture.allOf(
            tasks.stream().map(this::processAsync).toArray(CompletableFuture[]::new))
        .join();
  }

  @PreDestroy
  void stop() {
    executor.close();
  }

  private CompletableFuture<Void> processAsync(ClaimedTask task) {
    return CompletableFuture.runAsync(() -> process(task), executor)
        .orTimeout(properties.lease().toMillis(), MILLISECONDS)
        .exceptionally(
            error -> {
              log.error("Outbox task {} ({}) did not complete", task.id(), task.type(), error);
              return null;
            });
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
    Optional<Duration> delay = retrySchedule.delayAfter(failure, failedAttempts);
    if (delay.isPresent()) {
      queue.scheduleRetry(task.id(), delay.get(), failure.getMessage());
      log.warn(
          "Outbox task {} ({}) attempt {} failed, next try in {}: {}",
          task.id(),
          task.type(),
          failedAttempts,
          delay.get(),
          failure.getMessage());
      return;
    }
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
