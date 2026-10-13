package pt.paulotrevizan.appointments.integration.calendar;

import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Port to the doctor calendar system (HLD 7.3, D-006); Spring generates the implementation. Safe to
 * repeat: the appointment id is the idempotency key.
 */
public interface DoctorCalendarClient {

  @PostExchange("/doctors/{doctorId}/events")
  void addEvent(
      @PathVariable UUID doctorId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @RequestBody EventRequest event);

  /** Patient by id only, generic title: no health data leaves the service (HLD 8, D-027). */
  record EventRequest(
      UUID appointmentId, UUID patientId, Instant startAt, Instant endAt, String title) {

    public static final String TITLE = "Medical appointment";
  }
}
