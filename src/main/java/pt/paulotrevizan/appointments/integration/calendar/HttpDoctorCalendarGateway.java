package pt.paulotrevizan.appointments.integration.calendar;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.service.registry.ImportHttpServices;
import pt.paulotrevizan.appointments.integration.HttpFailures;
import pt.paulotrevizan.appointments.integration.calendar.DoctorCalendarClient.EventRequest;

@Component
@ImportHttpServices(group = "calendar", types = DoctorCalendarClient.class)
@RequiredArgsConstructor
class HttpDoctorCalendarGateway implements DoctorCalendarGateway {

  private static final String SYSTEM = "doctor calendar";
  // No specialty: the calendar entry must not reveal health data (HLD 8, D-027).
  private static final String EVENT_TITLE = "Medical appointment";

  private final DoctorCalendarClient client;

  @Override
  public void addEvent(CalendarEvent event) {
    try {
      client.addEvent(
          event.doctorId(),
          event.appointmentId(),
          new EventRequest(
              event.appointmentId(),
              event.patientId(),
              event.startAt(),
              event.endAt(),
              EVENT_TITLE));
    } catch (RestClientException e) {
      throw HttpFailures.classify(SYSTEM, e);
    }
  }
}
