package pt.paulotrevizan.appointments.booking;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import pt.paulotrevizan.appointments.postbooking.OutboxTask;
import pt.paulotrevizan.appointments.postbooking.OutboxTaskRepository;
import pt.paulotrevizan.appointments.postbooking.OutboxTaskType;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

  private final SlotPolicy slotPolicy;
  private final AllocationPolicy allocationPolicy;
  private final BookingProperties properties;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;
  private final PatientRepository patients;
  private final SpecialtyRepository specialties;
  private final AppointmentRepository appointments;
  private final OutboxTaskRepository outboxTasks;

  public Appointment book(UUID patientId, String specialtyCode, Instant startAt) {
    slotPolicy.validate(startAt);
    Patient patient =
        patients.findById(patientId).orElseThrow(() -> new PatientNotFoundException(patientId));
    Specialty specialty =
        specialties
            .findByCode(specialtyCode)
            .orElseThrow(() -> new SpecialtyNotFoundException(specialtyCode));

    Appointment appointment =
        transactionTemplate.execute(status -> bookInTransaction(patient, specialty, startAt));
    log.info("Appointment {} booked", appointment.getId());
    return appointment;
  }

  private Appointment bookInTransaction(Patient patient, Specialty specialty, Instant startAt) {
    Instant endAt = startAt.plus(properties.slotDuration());
    Doctor doctor = allocationPolicy.chooseDoctor(specialty, startAt, endAt);
    Room room = allocationPolicy.chooseRoom(startAt, endAt);
    // PostgreSQL stores microseconds; truncating keeps the response equal to what is read back.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

    Appointment appointment =
        appointments.saveAndFlush(
            new Appointment(
                patient, specialty, doctor, room, startAt, properties.slotDuration(), now));
    outboxTasks.saveAll(
        Arrays.stream(OutboxTaskType.values())
            .map(type -> new OutboxTask(appointment, type))
            .toList());
    return appointment;
  }
}
