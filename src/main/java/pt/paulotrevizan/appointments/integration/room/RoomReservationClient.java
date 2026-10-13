package pt.paulotrevizan.appointments.integration.room;

import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.PostExchange;

/**
 * HTTP contract of the room reservation system (HLD 7.3). Spring creates the client; base URL and
 * timeouts come from {@code spring.http.serviceclient.room.*} and {@code spring.http.clients.*}.
 */
interface RoomReservationClient {

  @PostExchange("/rooms/{roomId}/reservations")
  void reserve(
      @PathVariable UUID roomId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @RequestBody ReservationRequest reservation);

  record ReservationRequest(UUID appointmentId, Instant startAt, Instant endAt) {}
}
