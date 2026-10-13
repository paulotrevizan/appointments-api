package pt.paulotrevizan.appointments.integration.calendar;

import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.PostExchange;

/** Doctor calendar system (HLD 7.3). Calls are idempotent per appointment id (A7). */
public interface DoctorCalendarGateway {

  @PostExchange("/doctors/{doctorId}/events")
  void addEvent(
      @PathVariable UUID doctorId,
      @RequestHeader("Idempotency-Key") UUID appointmentId,
      @RequestBody CalendarEvent event);
}
