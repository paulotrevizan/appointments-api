package pt.paulotrevizan.appointments.integration.room;

import java.time.Instant;
import java.util.UUID;

public record RoomReservation(UUID appointmentId, Instant startAt, Instant endAt) {}
