package pt.paulotrevizan.appointments.integration.calendar;

import java.util.UUID;

/** Doctor calendar system (HLD 7.3). Calls are idempotent per appointment id (A7). */
public interface DoctorCalendarGateway {

  void addEvent(UUID doctorId, CalendarEvent event);
}
