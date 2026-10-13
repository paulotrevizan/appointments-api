package pt.paulotrevizan.appointments.booking;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import pt.paulotrevizan.appointments.common.ApiException;

@Slf4j
@Service
@RequiredArgsConstructor
class AppointmentBooker {

  private static final String DOCTOR_OVERLAP = "appointment_doctor_no_overlap";
  private static final String ROOM_OVERLAP = "appointment_room_no_overlap";
  private static final String PATIENT_OVERLAP = "appointment_patient_no_overlap";

  private final BookingProperties properties;
  private final BookingAttempt attempt;
  private final TransactionTemplate transactionTemplate;

  BookingResult book(UUID patientId, Specialty specialty, Instant startAt) {
    ApiException lastConflict = null;
    for (int number = 1; number <= properties.maxAttempts(); number++) {
      try {
        return transactionTemplate.execute(status -> attempt.book(patientId, specialty, startAt));
      } catch (DataIntegrityViolationException e) {
        lastConflict = conflictOf(e, specialty, startAt);
        log.info("Booking attempt {} hit {}", number, lastConflict.getCode());
      }
    }
    throw lastConflict;
  }

  private ApiException conflictOf(
      DataIntegrityViolationException e, Specialty specialty, Instant startAt) {
    String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
    if (message.contains(DOCTOR_OVERLAP)) {
      return new NoDoctorAvailableException(specialty.getCode(), startAt);
    }
    if (message.contains(ROOM_OVERLAP)) {
      return new NoRoomAvailableException(startAt);
    }
    if (message.contains(PATIENT_OVERLAP)) {
      return new PatientAlreadyBookedException(startAt);
    }
    throw e;
  }
}
