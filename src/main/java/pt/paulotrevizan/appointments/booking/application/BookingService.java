package pt.paulotrevizan.appointments.booking.application;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.booking.domain.NoDoctorAvailableException;
import pt.paulotrevizan.appointments.booking.domain.NoRoomAvailableException;
import pt.paulotrevizan.appointments.booking.domain.PatientAlreadyBookedException;
import pt.paulotrevizan.appointments.booking.domain.PatientNotFoundException;
import pt.paulotrevizan.appointments.booking.domain.SpecialtyNotFoundException;
import pt.paulotrevizan.appointments.booking.persistence.SlotConstraint;
import pt.paulotrevizan.appointments.catalog.domain.Patient;
import pt.paulotrevizan.appointments.catalog.domain.Specialty;
import pt.paulotrevizan.appointments.catalog.persistence.PatientRepository;
import pt.paulotrevizan.appointments.catalog.persistence.SpecialtyRepository;
import pt.paulotrevizan.appointments.common.web.ApiException;

@Service
@RequiredArgsConstructor
public class BookingService {

  private final SlotPolicy slotPolicy;
  private final PatientRepository patients;
  private final SpecialtyRepository specialties;
  private final AppointmentBooker booker;

  public Appointment book(UUID patientId, String specialtyCode, Instant startAt) {
    slotPolicy.validate(startAt);
    Patient patient =
        patients.findById(patientId).orElseThrow(() -> new PatientNotFoundException(patientId));
    Specialty specialty =
        specialties
            .findByCode(specialtyCode)
            .orElseThrow(() -> new SpecialtyNotFoundException(specialtyCode));
    try {
      return booker.book(patient, specialty, startAt);
    } catch (DataIntegrityViolationException e) {
      throw slotTaken(e, specialty, startAt);
    }
  }

  private static ApiException slotTaken(
      DataIntegrityViolationException e, Specialty specialty, Instant startAt) {
    SlotConstraint constraint = SlotConstraint.violatedBy(e).orElseThrow(() -> e);
    return switch (constraint) {
      case DOCTOR -> new NoDoctorAvailableException(specialty.getCode(), startAt);
      case ROOM -> new NoRoomAvailableException(startAt);
      case PATIENT -> new PatientAlreadyBookedException(startAt);
    };
  }
}
