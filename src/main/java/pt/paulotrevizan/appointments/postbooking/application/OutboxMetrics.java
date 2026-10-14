package pt.paulotrevizan.appointments.postbooking.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskStatus;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskType;
import pt.paulotrevizan.appointments.postbooking.persistence.OutboxTaskRepository;

/**
 * HLD 13: the two outbox alert candidates. Read from the database when metrics are collected, so
 * every instance reports the same values, with the database clock.
 */
@Component
@RequiredArgsConstructor
class OutboxMetrics implements MeterBinder {

  static final String OLDEST_PENDING_AGE = "appointments.outbox.oldest.pending.age";
  static final String FAILED = "appointments.outbox.failed";

  private final OutboxTaskRepository tasks;

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder(OLDEST_PENDING_AGE, tasks, OutboxTaskRepository::oldestPendingAgeSeconds)
        .description("How long the oldest PENDING post-booking task has waited")
        .baseUnit("seconds")
        .register(registry);
    for (OutboxTaskType type : OutboxTaskType.values()) {
      Gauge.builder(FAILED, tasks, repository -> failed(repository, type))
          .description("Post-booking tasks that need a person (FAILED)")
          .tag("type", type.name())
          .register(registry);
    }
  }

  private static double failed(OutboxTaskRepository tasks, OutboxTaskType type) {
    return tasks.countByTypeAndStatus(type, OutboxTaskStatus.FAILED);
  }
}
