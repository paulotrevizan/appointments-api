package pt.paulotrevizan.appointments.integration.email;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** SMTP server, credentials and timeouts are standard {@code spring.mail} settings. */
@Validated
@ConfigurationProperties("appointments.email")
public record EmailProperties(@NotBlank @Email String from) {}
