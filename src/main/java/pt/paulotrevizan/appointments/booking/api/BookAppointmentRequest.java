package pt.paulotrevizan.appointments.booking.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record BookAppointmentRequest(
    @NotNull UUID patientId, @NotBlank String specialty, @NotNull Instant startAt) {}
