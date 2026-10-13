package pt.paulotrevizan.appointments.booking.domain;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import pt.paulotrevizan.appointments.common.web.ApiException;

public class NoDoctorAvailableException extends ApiException {

  public NoDoctorAvailableException(String specialtyCode, Instant startAt) {
    super(
        HttpStatus.CONFLICT,
        "NO_DOCTOR_AVAILABLE",
        "No doctor available",
        "No " + specialtyCode + " doctor is free at " + startAt);
  }
}
