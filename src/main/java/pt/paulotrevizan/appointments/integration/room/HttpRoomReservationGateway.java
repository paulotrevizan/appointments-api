package pt.paulotrevizan.appointments.integration.room;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import pt.paulotrevizan.appointments.integration.HttpFailures;
import pt.paulotrevizan.appointments.integration.IntegrationProperties;

@Component
class HttpRoomReservationGateway implements RoomReservationGateway {

  private static final String SYSTEM = "room reservation";

  private final RestClient restClient;

  HttpRoomReservationGateway(RestClient.Builder builder, IntegrationProperties properties) {
    this.restClient = builder.baseUrl(properties.roomBaseUrl().toString()).build();
  }

  @Override
  public void reserve(RoomReservation reservation) {
    try {
      restClient
          .post()
          .uri("/rooms/{roomId}/reservations", reservation.roomId())
          .header("Idempotency-Key", reservation.appointmentId().toString())
          .body(
              new ReservationRequest(
                  reservation.appointmentId(), reservation.startAt(), reservation.endAt()))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      throw HttpFailures.classify(SYSTEM, e);
    }
  }

  private record ReservationRequest(UUID appointmentId, Instant startAt, Instant endAt) {}
}
