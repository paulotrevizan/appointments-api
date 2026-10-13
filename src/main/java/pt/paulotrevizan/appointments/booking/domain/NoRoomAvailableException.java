package pt.paulotrevizan.appointments.booking.domain;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import pt.paulotrevizan.appointments.common.web.ApiException;

public class NoRoomAvailableException extends ApiException {

  public NoRoomAvailableException(Instant startAt) {
    super(HttpStatus.CONFLICT, "NO_ROOM_AVAILABLE", "No room is free at " + startAt);
  }
}
