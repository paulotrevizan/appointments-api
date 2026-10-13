package pt.paulotrevizan.appointments.integration;

import java.net.http.HttpClient;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;
import pt.paulotrevizan.appointments.integration.calendar.DoctorCalendarClient;
import pt.paulotrevizan.appointments.integration.room.RoomReservationClient;

/**
 * Base URLs come from {@code spring.http.serviceclient.<group>.base-url}, timeouts from {@code
 * spring.http.clients.*}.
 */
@Configuration
@ImportHttpServices(group = "calendar", types = DoctorCalendarClient.class)
@ImportHttpServices(group = "room", types = RoomReservationClient.class)
class IntegrationConfig {

  /**
   * The JDK client defaults to HTTP/2 and, on plain {@code http://}, tries an h2c upgrade that many
   * servers and proxies cancel; the external systems are plain request/response APIs.
   */
  @Bean
  ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder() {
    return ClientHttpRequestFactoryBuilder.jdk()
        .withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1));
  }
}
