package pt.paulotrevizan.appointments.postbooking.application;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.booking.persistence.AppointmentRepository;
import pt.paulotrevizan.appointments.integration.email.EmailSender;

@Component
@RequiredArgsConstructor
class EmailTaskHandler {

  static final String SUBJECT = "Your appointment is confirmed";
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

  private final AppointmentRepository appointments;
  private final EmailSender emailSender;

  /** Reads the patient's address now, so the email goes to the current one (HLD 8). */
  void handle(UUID appointmentId) {
    Appointment appointment = appointments.findWithDetailsById(appointmentId).orElseThrow();
    emailSender.send(appointment.getPatient().getEmail(), SUBJECT, body(appointment));
  }

  // Only what the patient needs to attend. No specialty: it can reveal a condition (HLD 8).
  private static String body(Appointment appointment) {
    return """
        Hello %s,

        Your appointment is confirmed.

        Date: %s
        Time: %s - %s (UTC)
        Doctor: %s
        Room: %s

        Reference: %s
        """
        .formatted(
            appointment.getPatient().getName(),
            DATE.format(appointment.getStartAt()),
            TIME.format(appointment.getStartAt()),
            TIME.format(appointment.getEndAt()),
            appointment.getDoctor().getName(),
            appointment.getRoom().getName(),
            appointment.getId());
  }
}
