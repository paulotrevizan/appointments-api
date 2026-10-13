package pt.paulotrevizan.appointments.booking;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import pt.paulotrevizan.appointments.common.ApiException;

public class PatientNotFoundException extends ApiException {

  public PatientNotFoundException(UUID patientId) {
    super(
        HttpStatus.NOT_FOUND,
        "PATIENT_NOT_FOUND",
        "Patient not found",
        "Patient " + patientId + " not found");
  }
}
