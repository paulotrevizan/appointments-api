package pt.paulotrevizan.appointments.integration.room;

import java.time.Instant;
import java.util.UUID;
import pt.paulotrevizan.appointments.integration.IntegrationException;

/** Port to the external room reservation system (HLD 7.3). */
public interface RoomReservationGateway {

  /**
   * Reserves the room for the appointment. Safe to repeat: the appointment id is the idempotency
   * key.
   *
   * @throws IntegrationException when the room system fails or rejects the reservation
   */
  void reserve(RoomReservation reservation);

  record RoomReservation(UUID appointmentId, UUID roomId, Instant startAt, Instant endAt) {}
}
