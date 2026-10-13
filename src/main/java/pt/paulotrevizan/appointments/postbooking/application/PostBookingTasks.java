package pt.paulotrevizan.appointments.postbooking.application;

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

  public void scheduleFor(Appointment appointment) {
    outboxTasks.saveAll(
        Arrays.stream(OutboxTaskType.values())
            .map(type -> new OutboxTask(appointment, type))
            .toList());
  }
}
