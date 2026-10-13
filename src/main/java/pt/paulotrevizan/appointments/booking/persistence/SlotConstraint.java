package pt.paulotrevizan.appointments.booking.persistence;

import java.util.Arrays;
import java.util.Optional;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;

/** The unique indexes that guard a slot (I1, I2, I5), identified by name in PostgreSQL errors. */
public enum SlotConstraint {
  DOCTOR("appointment_doctor_no_overlap"),
  ROOM("appointment_room_no_overlap"),
  PATIENT("appointment_patient_no_overlap");

  private final String indexName;

  SlotConstraint(String indexName) {
    this.indexName = indexName;
  }

  public static Optional<SlotConstraint> violatedBy(Throwable error) {
    if (!(error instanceof DataIntegrityViolationException)) {
      return Optional.empty();
    }
    String message = NestedExceptionUtils.getMostSpecificCause(error).getMessage();
    return Arrays.stream(values())
        .filter(constraint -> message != null && message.contains(constraint.indexName))
        .findFirst();
  }
}
