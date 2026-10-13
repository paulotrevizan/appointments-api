package pt.paulotrevizan.appointments.common.web;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public abstract class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  protected ApiException(HttpStatus status, String code, String detail) {
    super(detail);
    this.status = status;
    this.code = code;
  }
}
