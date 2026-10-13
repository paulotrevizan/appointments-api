package pt.paulotrevizan.appointments.booking.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.booking.persistence.AppointmentRepository;
import pt.paulotrevizan.appointments.catalog.Doctor;
import pt.paulotrevizan.appointments.catalog.Patient;
import pt.paulotrevizan.appointments.catalog.Room;
import pt.paulotrevizan.appointments.catalog.Specialty;
import pt.paulotrevizan.appointments.postbooking.PostBookingTasks;

@Service
@RequiredArgsConstructor
class AppointmentBooker {

  private final AllocationPolicy allocationPolicy;
  private final BookingProperties properties;
  private final AppointmentRepository appointments;
  private final PostBookingTasks postBookingTasks;
  private final Clock clock;

  Appointment book(Patient patient, Specialty specialty, Instant startAt) {
    Instant endAt = startAt.plus(properties.slotDuration());
    Doctor doctor = allocationPolicy.chooseDoctor(specialty, startAt, endAt);
    Room room = allocationPolicy.chooseRoom(startAt, endAt);
    // PostgreSQL stores microseconds; truncating keeps the response equal to what is read back.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

    Appointment appointment =
        appointments.saveAndFlush(
            new Appointment(
                patient, specialty, doctor, room, startAt, properties.slotDuration(), now));
    postBookingTasks.scheduleFor(appointment);
    return appointment;
  }
}
