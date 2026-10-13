package pt.paulotrevizan.appointments.booking.domain;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import pt.paulotrevizan.appointments.common.ApiException;

public class SlotNotAlignedException extends ApiException {

  public SlotNotAlignedException(Instant startAt, Duration slotDuration) {
    super(
        HttpStatus.BAD_REQUEST,
        "SLOT_NOT_ALIGNED",
        "Slot not aligned",
        "Slot " + startAt + " is not aligned to the " + slotDuration.toMinutes() + "-minute grid");
  }
}
