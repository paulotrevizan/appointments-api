package pt.paulotrevizan.appointments.booking.application;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.booking.domain.PatientNotFoundException;
import pt.paulotrevizan.appointments.booking.domain.SpecialtyNotFoundException;
import pt.paulotrevizan.appointments.catalog.Patient;
import pt.paulotrevizan.appointments.catalog.PatientRepository;
import pt.paulotrevizan.appointments.catalog.Specialty;
import pt.paulotrevizan.appointments.catalog.SpecialtyRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

  private final SlotPolicy slotPolicy;
  private final PatientRepository patients;
  private final SpecialtyRepository specialties;
  private final AppointmentBooker booker;
  private final TransactionTemplate transactionTemplate;

  public Appointment book(UUID patientId, String specialtyCode, Instant startAt) {
    slotPolicy.validate(startAt);
    Patient patient =
        patients.findById(patientId).orElseThrow(() -> new PatientNotFoundException(patientId));
    Specialty specialty =
        specialties
            .findByCode(specialtyCode)
            .orElseThrow(() -> new SpecialtyNotFoundException(specialtyCode));

    Appointment appointment =
        transactionTemplate.execute(status -> booker.book(patient, specialty, startAt));
    log.info("Appointment {} booked", appointment.getId());
    return appointment;
  }
}
