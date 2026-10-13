package pt.paulotrevizan.appointments.postbooking.application;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.integration.calendar.DoctorCalendarClient;
import pt.paulotrevizan.appointments.integration.calendar.DoctorCalendarClient.EventRequest;
import pt.paulotrevizan.appointments.integration.room.RoomReservationClient;
import pt.paulotrevizan.appointments.integration.room.RoomReservationClient.ReservationRequest;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTask;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskType;

/** Runs one task against its external system; the appointment id is the idempotency key. */
@Component
@RequiredArgsConstructor
class PostBookingDispatcher {

  // EMAIL is added in Step 5; until then its tasks stay PENDING and are not picked up.
  static final List<String> HANDLED_TYPES =
      List.of(OutboxTaskType.DOCTOR_CALENDAR.name(), OutboxTaskType.ROOM_RESERVATION.name());

  private final DoctorCalendarClient calendar;
  private final RoomReservationClient rooms;

  void dispatch(OutboxTask task) {
    Appointment appointment = task.getAppointment();
    switch (task.getType()) {
      case DOCTOR_CALENDAR ->
          calendar.addEvent(
              appointment.getDoctor().getId(),
              appointment.getId(),
              new EventRequest(
                  appointment.getId(),
                  appointment.getPatient().getId(),
                  appointment.getStartAt(),
                  appointment.getEndAt(),
                  EventRequest.TITLE));
      case ROOM_RESERVATION ->
          rooms.reserve(
              appointment.getRoom().getId(),
              appointment.getId(),
              new ReservationRequest(
                  appointment.getId(), appointment.getStartAt(), appointment.getEndAt()));
      case EMAIL -> throw new IllegalStateException("No handler for " + task.getType());
    }
  }
}
