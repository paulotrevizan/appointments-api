package pt.paulotrevizan.appointments.integration;

import java.net.http.HttpClient;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;
import pt.paulotrevizan.appointments.integration.calendar.DoctorCalendarGateway;
import pt.paulotrevizan.appointments.integration.room.RoomReservationGateway;

/**
 * HTTP service clients for the external systems (D-006). Base URLs come from {@code
 * spring.http.serviceclient.<group>}; timeouts from {@code spring.http.clients}.
 */
@Configuration
@ImportHttpServices(group = "calendar", types = DoctorCalendarGateway.class)
@ImportHttpServices(group = "rooms", types = RoomReservationGateway.class)
class HttpServicesConfig {

  /** HTTP/1.1: the JDK client's default cleartext HTTP/2 upgrade is dropped by many servers. */
  @Bean
  ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder() {
    return ClientHttpRequestFactoryBuilder.jdk()
        .withHttpClientCustomizer(builder -> builder.version(HttpClient.Version.HTTP_1_1));
  }
}
