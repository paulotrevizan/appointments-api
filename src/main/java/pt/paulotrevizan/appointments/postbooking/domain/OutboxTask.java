package pt.paulotrevizan.appointments.postbooking.domain;

import jakarta.persistence.Column;
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
  private Instant lockedUntil;
  private String lastError;

  // Set by the database clock (column defaults), never by the application.
  @Column(insertable = false, updatable = false)
  private Instant nextAttemptAt;

  @Column(insertable = false, updatable = false)
  private Instant createdAt;

  @Column(insertable = false, updatable = false)
  private Instant updatedAt;

  public OutboxTask(Appointment appointment, OutboxTaskType type) {
    this.id = UUID.randomUUID();
    this.appointment = appointment;
    this.type = type;
    this.status = OutboxTaskStatus.PENDING;
  }
}
