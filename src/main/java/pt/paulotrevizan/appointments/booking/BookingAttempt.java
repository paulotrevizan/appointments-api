package pt.paulotrevizan.appointments.booking;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pt.paulotrevizan.appointments.postbooking.PostBookingTasks;

@Service
@RequiredArgsConstructor
class BookingAttempt {

  private final PatientRepository patients;
  private final AppointmentRepository appointments;
  private final AllocationPolicy allocationPolicy;
  private final PostBookingTasks postBookingTasks;
  private final Clock clock;

  /** Runs inside the caller's transaction. */
  BookingResult book(UUID patientId, Specialty specialty, Instant startAt) {
    Patient patient =
        patients
            .findByIdForUpdate(patientId)
            .orElseThrow(() -> new PatientNotFoundException(patientId));
    return appointments
        .findConfirmed(patient, startAt)
        .map(existing -> retryOf(existing, specialty, startAt))
        .orElseGet(() -> create(patient, specialty, startAt));
  }

  private BookingResult retryOf(Appointment existing, Specialty specialty, Instant startAt) {
    if (!existing.getSpecialty().getId().equals(specialty.getId())) {
      throw new PatientAlreadyBookedException(startAt);
    }
    return new BookingResult(existing, false);
  }

  private BookingResult create(Patient patient, Specialty specialty, Instant startAt) {
    Allocation allocation = allocationPolicy.allocate(specialty, startAt);
    // PostgreSQL stores microseconds; truncating keeps the response equal to what is read back.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Appointment appointment =
        appointments.saveAndFlush(
            new Appointment(
                patient,
                specialty,
                allocation.doctor(),
                allocation.room(),
                startAt,
                allocation.endAt(),
                now));
    postBookingTasks.scheduleFor(appointment);
    return new BookingResult(appointment, true);
  }
}
