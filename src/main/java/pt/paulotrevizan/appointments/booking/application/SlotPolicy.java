package pt.paulotrevizan.appointments.booking.application;

import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.booking.domain.SlotInPastException;
import pt.paulotrevizan.appointments.booking.domain.SlotNotAlignedException;

@Component
@RequiredArgsConstructor
class SlotPolicy {

  private final Clock clock;

  void validate(Instant startAt) {
    if (!startAt.isAfter(clock.instant())) {
      throw new SlotInPastException(startAt);
    }
    long gridSeconds = Appointment.SLOT_DURATION.toSeconds();
    if (startAt.getNano() != 0 || startAt.getEpochSecond() % gridSeconds != 0) {
      throw new SlotNotAlignedException(startAt, Appointment.SLOT_DURATION);
    }
  }
}
