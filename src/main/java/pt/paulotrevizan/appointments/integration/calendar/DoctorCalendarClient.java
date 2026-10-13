package pt.paulotrevizan.appointments.integration.calendar;

import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.PostExchange;

/**
 * HTTP contract of the doctor calendar system (HLD 7.3). Spring creates the client; base URL and
 * timeouts come from {@code spring.http.serviceclient.calendar.*} and {@code
 * spring.http.clients.*}.
 */
interface DoctorCalendarClient {

  @PostExchange("/doctors/{doctorId}/events")
  void addEvent(
      @PathVariable UUID doctorId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @RequestBody EventRequest event);

  record EventRequest(
      UUID appointmentId, UUID patientId, Instant startAt, Instant endAt, String title) {}
}
