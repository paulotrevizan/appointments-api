package pt.paulotrevizan.appointments.postbooking.application;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.booking.persistence.AppointmentRepository;
import pt.paulotrevizan.appointments.integration.room.RoomReservation;
import pt.paulotrevizan.appointments.integration.room.RoomReservationGateway;

@Component
@RequiredArgsConstructor
class RoomReservationTaskHandler {

  private final AppointmentRepository appointments;
  private final RoomReservationGateway rooms;

  void handle(UUID appointmentId) {
    Appointment appointment = appointments.findById(appointmentId).orElseThrow();
    rooms.reserve(
        appointment.getRoom().getId(),
        appointment.getId(),
        new RoomReservation(appointment.getId(), appointment.getStartAt(), appointment.getEndAt()));
  }
}
