package pt.paulotrevizan.appointments.integration.room;

import java.util.UUID;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import pt.paulotrevizan.appointments.integration.IntegrationProperties;

@Component
class HttpRoomReservationGateway implements RoomReservationGateway {

  private final RestClient restClient;

  HttpRoomReservationGateway(
      IntegrationProperties properties, ClientHttpRequestFactory externalRequestFactory) {
    this.restClient =
        RestClient.builder()
            .baseUrl(properties.roomsBaseUrl())
            .requestFactory(externalRequestFactory)
            .build();
  }

  @Override
  public void reserve(UUID roomId, RoomReservation reservation) {
    restClient
        .post()
        .uri("/rooms/{roomId}/reservations", roomId)
        .header("Idempotency-Key", reservation.appointmentId().toString())
        .body(reservation)
        .retrieve()
        .toBodilessEntity();
  }
}
