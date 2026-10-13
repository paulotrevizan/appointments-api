package pt.paulotrevizan.appointments.postbooking.application;

import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pt.paulotrevizan.appointments.integration.calendar.DoctorCalendarGateway;
import pt.paulotrevizan.appointments.integration.calendar.DoctorCalendarGateway.CalendarEvent;
import pt.paulotrevizan.appointments.integration.room.RoomReservationGateway;
import pt.paulotrevizan.appointments.integration.room.RoomReservationGateway.RoomReservation;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskType;
import pt.paulotrevizan.appointments.postbooking.persistence.ClaimedTask;

@Component
@RequiredArgsConstructor
class PostBookingDispatcher {

  // EMAIL is added in Step 5; until then its tasks stay PENDING and are not claimed.
  private static final Set<OutboxTaskType> HANDLED_TYPES =
      EnumSet.of(OutboxTaskType.DOCTOR_CALENDAR, OutboxTaskType.ROOM_RESERVATION);

  private final DoctorCalendarGateway calendar;
  private final RoomReservationGateway rooms;

  Set<OutboxTaskType> handledTypes() {
    return HANDLED_TYPES;
  }

  void dispatch(ClaimedTask task) {
    switch (task.type()) {
      case DOCTOR_CALENDAR ->
          calendar.addEvent(
              new CalendarEvent(
                  task.appointmentId(),
                  task.doctorId(),
                  task.patientId(),
                  task.startAt(),
                  task.endAt()));
      case ROOM_RESERVATION ->
          rooms.reserve(
              new RoomReservation(
                  task.appointmentId(), task.roomId(), task.startAt(), task.endAt()));
      case EMAIL -> throw new IllegalStateException("No handler for " + task.type());
    }
  }
}
