package pt.paulotrevizan.appointments.postbooking.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.common.persistence.BaseEntity;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxTask extends BaseEntity {

  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "appointment_id")
  private Appointment appointment;

  @Enumerated(EnumType.STRING)
  private OutboxTaskType type;

  @Enumerated(EnumType.STRING)
  private OutboxTaskStatus status;

  private int attempts;
  private Instant nextAttemptAt;
  private String lastError;
  private Instant createdAt;
  private Instant updatedAt;

  public OutboxTask(Appointment appointment, OutboxTaskType type, Instant now) {
    this.id = UUID.randomUUID();
    this.appointment = appointment;
    this.type = type;
    this.status = OutboxTaskStatus.PENDING;
    this.nextAttemptAt = now;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void markDone(Instant now) {
    this.status = OutboxTaskStatus.DONE;
    this.lastError = null;
    this.updatedAt = now;
  }

  /**
   * Counts a failed attempt. The task is retried at {@code nextAttemptAt}, or FAILED when there is
   * none: the failure is permanent or the attempts are used up (D-005, D-017).
   */
  public void recordFailure(String error, Instant now, Instant nextAttemptAt) {
    this.attempts++;
    this.lastError = error;
    this.updatedAt = now;
    if (nextAttemptAt == null) {
      this.status = OutboxTaskStatus.FAILED;
    } else {
      this.nextAttemptAt = nextAttemptAt;
    }
  }
}
