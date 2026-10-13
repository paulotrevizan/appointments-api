package pt.paulotrevizan.appointments.booking;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record BookAppointmentRequest(
    @Schema(
            description = "Id of an existing patient",
            example = "00000000-0000-0000-0000-000000000001")
        @NotNull
        UUID patientId,
    @Schema(
            description =
                "Specialty code: CARDIOLOGY, DERMATOLOGY, GENERAL_PRACTICE, PEDIATRICS,"
                    + " ORTHOPEDICS or NEUROLOGY",
            example = "CARDIOLOGY")
        @NotBlank
        String specialty,
    @Schema(
            description = "Future UTC instant aligned to the 30-minute grid (:00 or :30)",
            example = "2030-01-07T14:00:00Z")
        @NotNull
        Instant startAt) {}
