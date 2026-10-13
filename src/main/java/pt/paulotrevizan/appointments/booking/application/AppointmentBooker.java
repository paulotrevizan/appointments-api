package pt.paulotrevizan.appointments.booking.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.booking.domain.PatientAlreadyBookedException;
import pt.paulotrevizan.appointments.booking.persistence.AppointmentRepository;
import pt.paulotrevizan.appointments.catalog.domain.Patient;
import pt.paulotrevizan.appointments.catalog.domain.Specialty;
import pt.paulotrevizan.appointments.postbooking.application.PostBookingTasks;

@Slf4j
@Service
@RequiredArgsConstructor
class AppointmentBooker {

  private final AllocationPolicy allocationPolicy;
  private final AppointmentRepository appointments;
  private final PostBookingTasks postBookingTasks;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  /**
   * One attempt per transaction (D-003). A request that loses the race for a doctor, room or
   * patient slot is retried; the next attempt reads the winner and picks another candidate, or
   * returns the winner's appointment when it was the same request (D-011).
   */
  @Retryable(
      predicate = SlotTakenRetryPredicate.class,
      maxRetriesString = "${appointments.booking.max-retries}",
      delay = 10,
      jitter = 5)
  public Appointment book(Patient patient, Specialty specialty, Instant startAt) {
    return transactionTemplate.execute(
        status ->
            findRepeated(patient, specialty, startAt)
                .orElseGet(() -> create(patient, specialty, startAt)));
  }

  private Optional<Appointment> findRepeated(
      Patient patient, Specialty specialty, Instant startAt) {
    return appointments
        .findConfirmed(patient, startAt)
        .map(
            existing -> {
              if (!existing.getSpecialty().getId().equals(specialty.getId())) {
                throw new PatientAlreadyBookedException(startAt);
              }
              log.info("Repeated request for appointment {}", existing.getId());
              return existing;
            });
  }

  private Appointment create(Patient patient, Specialty specialty, Instant startAt) {
    Instant endAt = startAt.plus(Appointment.SLOT_DURATION);
    // PostgreSQL stores microseconds; truncating keeps the response equal to what is read back.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Appointment appointment =
        appointments.saveAndFlush(
            new Appointment(
                patient,
                specialty,
                allocationPolicy.chooseDoctor(specialty, startAt, endAt),
                allocationPolicy.chooseRoom(startAt, endAt),
                startAt,
                now));
    postBookingTasks.scheduleFor(appointment);
    log.info("Appointment {} booked", appointment.getId());
    return appointment;
  }
}
