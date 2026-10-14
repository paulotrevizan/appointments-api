package pt.paulotrevizan.appointments.booking.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import pt.paulotrevizan.appointments.booking.persistence.SlotConstraint;

/** HLD 13: booking outcomes and latency, and slot conflicts as a contention signal. */
@Component
class BookingMetrics {

  static final String BOOKINGS = "appointments.bookings";
  static final String CONFLICTS = "appointments.booking.conflicts";
  static final String BOOKED = "BOOKED";
  static final String REPEATED = "REPEATED";
  static final String ERROR = "ERROR";

  private final MeterRegistry registry;
  private final Map<SlotConstraint, Counter> conflicts = new EnumMap<>(SlotConstraint.class);

  BookingMetrics(MeterRegistry registry) {
    this.registry = registry;
    // Registered up front, so dashboards show 0 instead of no data.
    for (SlotConstraint constraint : SlotConstraint.values()) {
      conflicts.put(
          constraint,
          Counter.builder(CONFLICTS)
              .description("Booking attempts that lost a slot to a concurrent request (D-003)")
              .tag("resource", constraint.name().toLowerCase(Locale.ROOT))
              .register(registry));
    }
  }

  Timer.Sample start() {
    return Timer.start(registry);
  }

  /** {@code outcome}: BOOKED, REPEATED, the error code of the response, or ERROR. */
  void recordBooking(Timer.Sample sample, String outcome) {
    sample.stop(
        Timer.builder(BOOKINGS)
            .description("Booking requests that reached the service, by outcome")
            .tag("outcome", outcome)
            .register(registry));
  }

  void recordConflict(SlotConstraint constraint) {
    conflicts.get(constraint).increment();
  }
}
