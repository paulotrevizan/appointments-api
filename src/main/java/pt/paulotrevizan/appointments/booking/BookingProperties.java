package pt.paulotrevizan.appointments.booking;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("appointments.booking")
public record BookingProperties(@DefaultValue("30m") Duration slotDuration) {}
