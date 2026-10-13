package pt.paulotrevizan.appointments.booking;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SlotPolicyTest {

  private static final Instant NOW = Instant.parse("2030-01-07T10:00:00Z");

  private final SlotPolicy policy =
      new SlotPolicy(
          new BookingProperties(Duration.ofMinutes(30), 3), Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  @DisplayName("D-007: a future slot on the grid is accepted")
  void givenFutureSlotOnGrid_whenValidated_thenAccepted() {
    assertThatCode(() -> policy.validate(Instant.parse("2030-01-07T10:30:00Z")))
        .doesNotThrowAnyException();
    assertThatCode(() -> policy.validate(Instant.parse("2030-01-08T14:00:00Z")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("A slot in the past is rejected")
  void givenPastSlot_whenValidated_thenSlotInPast() {
    assertThatThrownBy(() -> policy.validate(Instant.parse("2030-01-07T09:30:00Z")))
        .isInstanceOf(SlotInPastException.class);
  }

  @Test
  @DisplayName("A slot starting exactly now is not in the future")
  void givenSlotStartingNow_whenValidated_thenSlotInPast() {
    assertThatThrownBy(() -> policy.validate(NOW)).isInstanceOf(SlotInPastException.class);
  }

  @Test
  @DisplayName("D-007: a slot off the 30-minute grid is rejected")
  void givenUnalignedSlot_whenValidated_thenSlotNotAligned() {
    assertThatThrownBy(() -> policy.validate(Instant.parse("2030-01-07T14:15:00Z")))
        .isInstanceOf(SlotNotAlignedException.class);
    assertThatThrownBy(() -> policy.validate(Instant.parse("2030-01-07T14:00:01Z")))
        .isInstanceOf(SlotNotAlignedException.class);
    assertThatThrownBy(() -> policy.validate(Instant.parse("2030-01-07T14:00:00.5Z")))
        .isInstanceOf(SlotNotAlignedException.class);
  }
}
