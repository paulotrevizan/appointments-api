package pt.paulotrevizan.appointments.booking;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import pt.paulotrevizan.appointments.common.ApiException;

public class PatientAlreadyBookedException extends ApiException {

  public PatientAlreadyBookedException(Instant startAt) {
    super(
        HttpStatus.CONFLICT,
        "PATIENT_ALREADY_BOOKED",
        "Patient already booked",
        "The patient already has another appointment at " + startAt);
  }
}
