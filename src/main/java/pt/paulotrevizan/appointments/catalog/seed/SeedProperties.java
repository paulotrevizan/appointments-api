package pt.paulotrevizan.appointments.catalog.seed;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("appointments.seed")
public record SeedProperties(
    @Min(1) int minDoctorsPerSpecialty,
    @Min(1) int maxDoctorsPerSpecialty,
    @Min(1) int rooms,
    @Min(0) int patients) {}
