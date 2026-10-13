package pt.paulotrevizan.appointments.integration.calendar;

import java.util.UUID;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import pt.paulotrevizan.appointments.integration.IntegrationProperties;

@Component
class HttpDoctorCalendarGateway implements DoctorCalendarGateway {

  private final RestClient restClient;

  HttpDoctorCalendarGateway(
      IntegrationProperties properties, ClientHttpRequestFactory externalRequestFactory) {
    this.restClient =
        RestClient.builder()
            .baseUrl(properties.calendarBaseUrl())
            .requestFactory(externalRequestFactory)
            .build();
  }

  @Override
  public void addEvent(UUID doctorId, CalendarEvent event) {
    restClient
        .post()
        .uri("/doctors/{doctorId}/events", doctorId)
        .header("Idempotency-Key", event.appointmentId().toString())
        .body(event)
        .retrieve()
        .toBodilessEntity();
  }
}
