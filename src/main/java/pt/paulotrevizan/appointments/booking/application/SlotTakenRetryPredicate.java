package pt.paulotrevizan.appointments.booking.application;

import java.lang.reflect.Method;
import org.springframework.resilience.retry.MethodRetryPredicate;
import pt.paulotrevizan.appointments.booking.persistence.SlotConstraint;

/** Retry only when another request took the doctor, room or patient slot first (D-003). */
class SlotTakenRetryPredicate implements MethodRetryPredicate {

  @Override
  public boolean shouldRetry(Method method, Throwable error) {
    return SlotConstraint.violatedBy(error).isPresent();
  }
}
