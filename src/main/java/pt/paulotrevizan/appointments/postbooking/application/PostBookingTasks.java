package pt.paulotrevizan.appointments.postbooking.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTask;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskType;
import pt.paulotrevizan.appointments.postbooking.persistence.OutboxTaskRepository;

@Component
@RequiredArgsConstructor
public class PostBookingTasks {

  private final OutboxTaskRepository outboxTasks;
  private final Clock clock;

  public void scheduleFor(Appointment appointment) {
    Instant now = clock.instant();
    outboxTasks.saveAll(
        Arrays.stream(OutboxTaskType.values())
            .map(type -> new OutboxTask(appointment, type, now))
            .toList());
  }
}
