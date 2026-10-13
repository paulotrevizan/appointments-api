package pt.paulotrevizan.appointments.integration;

import java.net.http.HttpClient;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class IntegrationConfig {

  /**
   * The JDK client defaults to HTTP/2 and, on plain {@code http://}, tries an h2c upgrade that many
   * servers and proxies cancel. The external systems are plain request/response APIs, so HTTP/1.1
   * is used everywhere; timeouts still come from {@code spring.http.clients.*}.
   */
  @Bean
  ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder() {
    return ClientHttpRequestFactoryBuilder.jdk()
        .withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1));
  }
}
