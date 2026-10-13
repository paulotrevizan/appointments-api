package pt.paulotrevizan.appointments.booking.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import pt.paulotrevizan.appointments.catalog.domain.Doctor;
import pt.paulotrevizan.appointments.catalog.domain.Patient;
import pt.paulotrevizan.appointments.catalog.domain.Room;
import pt.paulotrevizan.appointments.catalog.domain.Specialty;
import pt.paulotrevizan.appointments.common.persistence.BaseEntity;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Appointment extends BaseEntity {

  // D-007: fixed by the schema (appointment_fixed_duration_check, appointment_grid_check).
  public static final Duration SLOT_DURATION = Duration.ofMinutes(30);

  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "patient_id")
  private Patient patient;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "specialty_id")
  private Specialty specialty;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "doctor_id")
  private Doctor doctor;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "room_id")
  private Room room;

  private Instant startAt;
  private Instant endAt;

  @Enumerated(EnumType.STRING)
  private AppointmentStatus status;

  private Instant createdAt;
  private Instant updatedAt;

  public Appointment(
      Patient patient,
      Specialty specialty,
      Doctor doctor,
      Room room,
      Instant startAt,
      Instant now) {
    this.id = UUID.randomUUID();
    this.patient = patient;
    this.specialty = specialty;
    this.doctor = doctor;
    this.room = room;
    this.startAt = startAt;
    this.endAt = startAt.plus(SLOT_DURATION);
    this.status = AppointmentStatus.CONFIRMED;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void cancel(Instant now) {
    this.status = AppointmentStatus.CANCELLED;
    this.updatedAt = now;
  }
}
