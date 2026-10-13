package pt.paulotrevizan.appointments.booking;

/** {@code created} is false when the request was a retry of an appointment that already exists. */
public record BookingResult(Appointment appointment, boolean created) {}
