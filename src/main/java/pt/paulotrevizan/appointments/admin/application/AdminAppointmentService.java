package pt.paulotrevizan.appointments.admin.application;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import pt.paulotrevizan.appointments.admin.application.CursorCodec.Cursor;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.booking.persistence.AppointmentRepository;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTask;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskStatus;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskType;
import pt.paulotrevizan.appointments.postbooking.persistence.OutboxTaskRepository;

/** HLD 6.3: keyset pagination in two queries per page, whatever the page size. */
@Service
@RequiredArgsConstructor
public class AdminAppointmentService {

  private final AppointmentRepository appointments;
  private final OutboxTaskRepository outboxTasks;
  private final CursorCodec cursorCodec;

  public AppointmentPage list(int limit, String cursor) {
    // One row more than asked tells whether there is a next page, without counting.
    Limit oneMore = Limit.of(limit + 1);
    List<Appointment> rows =
        cursor == null
            ? appointments.findFirstPage(oneMore)
            : findPageAfter(cursorCodec.decode(cursor), oneMore);
    boolean hasNext = rows.size() > limit;
    List<Appointment> page = hasNext ? rows.subList(0, limit) : rows;
    String nextCursor = hasNext ? cursorCodec.encode(Cursor.after(page.getLast())) : null;
    return new AppointmentPage(page, postBookingStatuses(page), nextCursor);
  }

  private List<Appointment> findPageAfter(Cursor cursor, Limit limit) {
    return appointments.findPageAfter(cursor.startAt(), cursor.id(), limit);
  }

  private Map<UUID, Map<OutboxTaskType, OutboxTaskStatus>> postBookingStatuses(
      List<Appointment> page) {
    if (page.isEmpty()) {
      return Map.of();
    }
    return outboxTasks
        .findByAppointmentIdIn(page.stream().map(Appointment::getId).toList())
        .stream()
        .collect(
            groupingBy(
                task -> task.getAppointment().getId(),
                toMap(OutboxTask::getType, OutboxTask::getStatus)));
  }
}
