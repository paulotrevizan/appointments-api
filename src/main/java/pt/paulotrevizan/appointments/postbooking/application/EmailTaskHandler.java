package pt.paulotrevizan.appointments.postbooking.application;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.booking.persistence.AppointmentRepository;
import pt.paulotrevizan.appointments.integration.email.EmailProperties;
import pt.paulotrevizan.appointments.integration.email.EmailSender;

@Component
@RequiredArgsConstructor
class EmailTaskHandler {

  static final String SUBJECT = "Your appointment is confirmed";
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

  private final AppointmentRepository appointments;
  private final EmailSender emailSender;
  private final EmailProperties properties;

  /** Reads the patient's address now, so the email goes to the current one (HLD 8). */
  void handle(UUID appointmentId) {
    Appointment appointment = appointments.findWithDetailsById(appointmentId).orElseThrow();
    emailSender.send(
        appointment.getPatient().getEmail(), SUBJECT, body(appointment, properties.timeZone()));
  }

  // Only what the patient needs to attend. No specialty: it can reveal a condition (HLD 8).
  // Times are the patient's local time (D-027), named by zone so they are never ambiguous.
  private static String body(Appointment appointment, ZoneId zone) {
    return """
        Hello %s,

        Your appointment is confirmed.

        Date: %s
        Time: %s - %s (%s time)
        Doctor: %s
        Room: %s

        Reference: %s
        """
        .formatted(
            appointment.getPatient().getName(),
            DATE.withZone(zone).format(appointment.getStartAt()),
            TIME.withZone(zone).format(appointment.getStartAt()),
            TIME.withZone(zone).format(appointment.getEndAt()),
            cityOf(zone),
            appointment.getDoctor().getName(),
            appointment.getRoom().getName(),
            appointment.getId());
  }

  // "Europe/Lisbon" → "Lisbon", "Atlantic/Azores" → "Azores": what a patient recognises.
  private static String cityOf(ZoneId zone) {
    String id = zone.getId();
    return id.substring(id.lastIndexOf('/') + 1).replace('_', ' ');
  }
}
