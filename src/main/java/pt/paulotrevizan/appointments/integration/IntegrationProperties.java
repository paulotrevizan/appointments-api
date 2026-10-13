package pt.paulotrevizan.appointments.integration;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Timeouts come from {@code spring.http.clients.*}, shared by every {@code RestClient}. */
@Validated
@ConfigurationProperties("appointments.integration")
public record IntegrationProperties(@NotNull URI calendarBaseUrl, @NotNull URI roomBaseUrl) {}
