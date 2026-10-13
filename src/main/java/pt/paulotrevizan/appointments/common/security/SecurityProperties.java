package pt.paulotrevizan.appointments.common.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("appointments.security")
public record SecurityProperties(
    @NotBlank String bookingClientPassword,
    @NotBlank String adminPassword,
    @DefaultValue("false") boolean publicApiDocs) {}
