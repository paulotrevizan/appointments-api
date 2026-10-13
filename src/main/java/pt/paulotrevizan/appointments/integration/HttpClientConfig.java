package pt.paulotrevizan.appointments.integration;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

@Configuration
@EnableConfigurationProperties(IntegrationProperties.class)
class HttpClientConfig {

  /**
   * Shared by the HTTP adapters: no call to an external system may wait without a bound. HTTP/1.1
   * because the JDK client's default cleartext HTTP/2 upgrade is dropped by many servers.
   */
  @Bean
  JdkClientHttpRequestFactory externalRequestFactory(IntegrationProperties properties) {
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build());
    factory.setReadTimeout(properties.readTimeout());
    return factory;
  }
}
