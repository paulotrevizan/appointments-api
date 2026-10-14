package pt.paulotrevizan.appointments.admin.domain;

import org.springframework.http.HttpStatus;
import pt.paulotrevizan.appointments.common.web.ApiException;

public class InvalidCursorException extends ApiException {

  public InvalidCursorException() {
    super(
        HttpStatus.BAD_REQUEST,
        "INVALID_CURSOR",
        "The cursor is malformed; use the nextCursor of a previous response");
  }
}
