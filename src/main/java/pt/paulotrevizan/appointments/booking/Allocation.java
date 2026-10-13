package pt.paulotrevizan.appointments.booking;

import java.time.Instant;

record Allocation(Doctor doctor, Room room, Instant endAt) {}
