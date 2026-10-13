package pt.paulotrevizan.appointments.integration.room;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.service.registry.ImportHttpServices;
import pt.paulotrevizan.appointments.integration.HttpFailures;
import pt.paulotrevizan.appointments.integration.room.RoomReservationClient.ReservationRequest;

@Component
@ImportHttpServices(group = "room", types = RoomReservationClient.class)
@RequiredArgsConstructor
class HttpRoomReservationGateway implements RoomReservationGateway {

  private static final String SYSTEM = "room reservation";

  private final RoomReservationClient client;

  @Override
  public void reserve(RoomReservation reservation) {
    try {
      client.reserve(
          reservation.roomId(),
          reservation.appointmentId(),
          new ReservationRequest(
              reservation.appointmentId(), reservation.startAt(), reservation.endAt()));
    } catch (RestClientException e) {
      throw HttpFailures.classify(SYSTEM, e);
    }
  }
}
