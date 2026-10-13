package pt.paulotrevizan.appointments.integration;

/** A call to an external system failed; D-017 decides whether retrying can help. */
public class IntegrationException extends RuntimeException {

  private final boolean retryable;

  private IntegrationException(String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.retryable = retryable;
  }

  public static IntegrationException retryable(String message, Throwable cause) {
    return new IntegrationException(message, true, cause);
  }

  public static IntegrationException permanent(String message, Throwable cause) {
    return new IntegrationException(message, false, cause);
  }

  public boolean isRetryable() {
    return retryable;
  }
}
