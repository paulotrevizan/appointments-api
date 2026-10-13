package pt.paulotrevizan.appointments.booking;

import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(BookingProperties.class)
@RequiredArgsConstructor
class SlotPolicy {

  private final BookingProperties properties;
  private final Clock clock;

  void validate(Instant startAt) {
    if (!startAt.isAfter(clock.instant())) {
      throw new SlotInPastException(startAt);
    }
    long gridSeconds = properties.slotDuration().toSeconds();
    if (startAt.getNano() != 0 || startAt.getEpochSecond() % gridSeconds != 0) {
      throw new SlotNotAlignedException(startAt, properties.slotDuration());
    }
  }
}
