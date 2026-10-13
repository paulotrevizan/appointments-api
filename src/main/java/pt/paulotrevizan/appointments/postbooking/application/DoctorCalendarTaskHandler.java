package pt.paulotrevizan.appointments.postbooking.application;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.booking.persistence.AppointmentRepository;
import pt.paulotrevizan.appointments.integration.calendar.CalendarEvent;
import pt.paulotrevizan.appointments.integration.calendar.DoctorCalendarGateway;

@Component
@RequiredArgsConstructor
class DoctorCalendarTaskHandler {

  private final AppointmentRepository appointments;
  private final DoctorCalendarGateway calendar;

  void handle(UUID appointmentId) {
    Appointment appointment = appointments.findWithRoomById(appointmentId).orElseThrow();
    // No specialty or patient data: it would reveal health data to another system (HLD 8).
    calendar.addEvent(
        appointment.getDoctor().getId(),
        appointment.getId(),
        new CalendarEvent(
            appointment.getId(),
            appointment.getStartAt(),
            appointment.getEndAt(),
            "Appointment in " + appointment.getRoom().getName()));
  }
}
