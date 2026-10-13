package pt.paulotrevizan.appointments.booking.domain;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import pt.paulotrevizan.appointments.common.web.ApiException;

public class PatientNotFoundException extends ApiException {

  public PatientNotFoundException(UUID patientId) {
    super(HttpStatus.NOT_FOUND, "PATIENT_NOT_FOUND", "Patient " + patientId + " not found");
  }
}
