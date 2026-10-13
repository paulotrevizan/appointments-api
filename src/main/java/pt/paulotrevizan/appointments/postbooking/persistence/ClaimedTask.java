package pt.paulotrevizan.appointments.postbooking.persistence;

import java.time.Instant;
import java.util.UUID;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskType;

/** A due task leased to this instance, with the appointment data its handler needs. */
public record ClaimedTask(
    UUID id,
    UUID appointmentId,
    OutboxTaskType type,
    int attempts,
    UUID doctorId,
    UUID roomId,
    Instant startAt,
    Instant endAt) {}
