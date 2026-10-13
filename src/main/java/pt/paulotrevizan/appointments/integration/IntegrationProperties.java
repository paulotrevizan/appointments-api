package pt.paulotrevizan.appointments.integration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Outbound HTTP systems (HLD 7.3). The outbox lease must stay longer than the timeouts. */
@Validated
@ConfigurationProperties("appointments.integration")
public record IntegrationProperties(
    @NotBlank String calendarBaseUrl,
    @NotBlank String roomsBaseUrl,
    @NotNull Duration connectTimeout,
    @NotNull Duration readTimeout) {}
