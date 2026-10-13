package pt.paulotrevizan.appointments.integration.room;

import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.PostExchange;

/** Room reservation system (HLD 7.3). Calls are idempotent per appointment id (A7). */
public interface RoomReservationGateway {

  @PostExchange("/rooms/{roomId}/reservations")
  void reserve(
      @PathVariable UUID roomId,
      @RequestHeader("Idempotency-Key") UUID appointmentId,
      @RequestBody RoomReservation reservation);
}
