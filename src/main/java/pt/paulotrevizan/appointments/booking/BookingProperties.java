package pt.paulotrevizan.appointments.booking;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("appointments.booking")
public record BookingProperties(
    @DefaultValue("30m") Duration slotDuration, @DefaultValue("3") @Min(1) int maxAttempts) {}
