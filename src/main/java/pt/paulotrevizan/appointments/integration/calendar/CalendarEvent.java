package pt.paulotrevizan.appointments.integration.calendar;

import java.time.Instant;
import java.util.UUID;

public record CalendarEvent(UUID appointmentId, Instant startAt, Instant endAt, String title) {}
