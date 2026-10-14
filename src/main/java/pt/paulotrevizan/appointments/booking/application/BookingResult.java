package pt.paulotrevizan.appointments.booking.application;

import pt.paulotrevizan.appointments.booking.domain.Appointment;

/**
 * {@code repeated}: the patient already had this appointment, so nothing new was booked (D-011).
 */
record BookingResult(Appointment appointment, boolean repeated) {

  static BookingResult booked(Appointment appointment) {
    return new BookingResult(appointment, false);
  }

  static BookingResult repeated(Appointment appointment) {
    return new BookingResult(appointment, true);
  }
}
