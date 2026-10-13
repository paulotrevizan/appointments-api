package pt.paulotrevizan.appointments.integration.calendar;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import pt.paulotrevizan.appointments.integration.HttpFailures;
import pt.paulotrevizan.appointments.integration.IntegrationProperties;

@Component
class HttpDoctorCalendarGateway implements DoctorCalendarGateway {

  private static final String SYSTEM = "doctor calendar";
  // No specialty or patient data: the calendar entry must not reveal health data (HLD 8).
  private static final String EVENT_TITLE = "Medical appointment";

  private final RestClient restClient;

  HttpDoctorCalendarGateway(RestClient.Builder builder, IntegrationProperties properties) {
    this.restClient = builder.baseUrl(properties.calendarBaseUrl().toString()).build();
  }

  @Override
  public void addEvent(CalendarEvent event) {
    try {
      restClient
          .post()
          .uri("/doctors/{doctorId}/events", event.doctorId())
          .header("Idempotency-Key", event.appointmentId().toString())
          .body(
              new EventRequest(event.appointmentId(), event.startAt(), event.endAt(), EVENT_TITLE))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      throw HttpFailures.classify(SYSTEM, e);
    }
  }

  private record EventRequest(UUID appointmentId, Instant startAt, Instant endAt, String title) {}
}
