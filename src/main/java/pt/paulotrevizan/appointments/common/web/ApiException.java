package pt.paulotrevizan.appointments.common.web;

import org.springframework.http.HttpStatus;

public abstract class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  protected ApiException(HttpStatus status, String code, String detail) {
    super(detail);
    this.status = status;
    this.code = code;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }
}
