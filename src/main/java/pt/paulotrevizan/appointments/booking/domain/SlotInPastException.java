package pt.paulotrevizan.appointments.booking.domain;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import pt.paulotrevizan.appointments.common.ApiException;

public class SlotInPastException extends ApiException {

  public SlotInPastException(Instant startAt) {
    super(
        HttpStatus.BAD_REQUEST,
        "SLOT_IN_PAST",
        "Slot in the past",
        "Slot " + startAt + " is not in the future");
  }
}
