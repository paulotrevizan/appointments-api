package pt.paulotrevizan.appointments.common.web;

import lombok.Getter;
import org.springframework.http.HttpStatus;

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
