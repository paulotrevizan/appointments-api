package pt.paulotrevizan.appointments.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import pt.paulotrevizan.appointments.common.BaseEntity;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Patient extends BaseEntity {

  @Id private UUID id;
  private String name;
  private String email;
  private String phone;
  private Instant createdAt;
  private Instant updatedAt;

  public Patient(UUID id, String name, String email, String phone, Instant now) {
    this.id = id;
    this.name = name;
    this.email = email;
    this.phone = phone;
    this.createdAt = now;
    this.updatedAt = now;
  }
}
