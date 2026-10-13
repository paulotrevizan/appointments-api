package pt.paulotrevizan.appointments.postbooking.application;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Outbox worker settings (HLD 6.2). {@code enabled} and {@code poll-interval} drive scheduling. */
@Validated
@ConfigurationProperties("appointments.outbox")
public record OutboxProperties(
    @Positive int batchSize,
    @NotNull Duration lease,
    @Positive int maxAttempts,
    @NotNull Duration initialBackoff,
    @NotNull Duration maxBackoff,
    @DecimalMin("1") double backoffMultiplier,
    @DecimalMin("0") @DecimalMax("1") double backoffJitter) {}
