package pt.paulotrevizan.appointments.booking;

import org.springframework.http.HttpStatus;
import pt.paulotrevizan.appointments.common.ApiException;

public class SpecialtyNotFoundException extends ApiException {

  public SpecialtyNotFoundException(String code) {
    super(
        HttpStatus.NOT_FOUND,
        "SPECIALTY_NOT_FOUND",
        "Specialty not found",
        "Specialty " + code + " not found");
  }
}
