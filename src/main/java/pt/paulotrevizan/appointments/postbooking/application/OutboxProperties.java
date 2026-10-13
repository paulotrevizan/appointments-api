package pt.paulotrevizan.appointments.postbooking.application;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** The worker switch and the poll interval are read by {@code OutboxWorker}'s annotations. */
@Validated
@ConfigurationProperties("appointments.outbox")
public record OutboxProperties(
    @Min(1) int batchSize,
    @Min(1) int maxAttempts,
    @NotNull Duration initialDelay,
    @NotNull Duration maxDelay,
    @DecimalMin("0.0") @DecimalMax("1.0") double jitter) {}
