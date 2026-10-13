package pt.paulotrevizan.appointments.integration.room;

import java.util.UUID;

/** Room reservation system (HLD 7.3). Calls are idempotent per appointment id (A7). */
public interface RoomReservationGateway {

  void reserve(UUID roomId, RoomReservation reservation);
}
