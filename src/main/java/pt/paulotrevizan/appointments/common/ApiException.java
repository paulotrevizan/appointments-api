package pt.paulotrevizan.appointments.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Base of the domain exceptions: one subclass per error {@code code} in the HLD contract. */
@Getter
public abstract class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;
  private final String title;

  protected ApiException(HttpStatus status, String code, String title, String detail) {
    super(detail);
    this.status = status;
    this.code = code;
    this.title = title;
  }
}
