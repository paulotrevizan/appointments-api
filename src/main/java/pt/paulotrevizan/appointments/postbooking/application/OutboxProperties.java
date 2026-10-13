package pt.paulotrevizan.appointments.postbooking.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** The poll interval and the worker switch are read directly by {@code OutboxWorker}. */
@Validated
@ConfigurationProperties("appointments.outbox")
public record OutboxProperties(
    @Min(1) int batchSize,
    @NotNull Duration lease,
    @Min(1) int maxAttempts,
    @Valid @NotNull Backoff backoff) {

  public record Backoff(
      @NotNull Duration initial,
      @DecimalMin("1.0") double multiplier,
      @NotNull Duration max,
      @NotNull Duration jitter) {}
}
