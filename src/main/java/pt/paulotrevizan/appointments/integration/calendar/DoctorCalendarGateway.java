package pt.paulotrevizan.appointments.integration.calendar;

import java.time.Instant;
import java.util.UUID;
import pt.paulotrevizan.appointments.integration.IntegrationException;

/** Port to the external doctor calendar system (HLD 7.3). */
public interface DoctorCalendarGateway {

  /**
   * Adds the appointment to the doctor's calendar. Safe to repeat: the appointment id is the
   * idempotency key.
   *
   * @throws IntegrationException when the calendar system fails or rejects the event
   */
  void addEvent(CalendarEvent event);

  record CalendarEvent(UUID appointmentId, UUID doctorId, Instant startAt, Instant endAt) {}
}
