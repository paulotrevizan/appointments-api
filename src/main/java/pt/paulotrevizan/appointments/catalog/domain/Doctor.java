package pt.paulotrevizan.appointments.catalog.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import pt.paulotrevizan.appointments.common.persistence.BaseEntity;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Doctor extends BaseEntity {

  @Id private UUID id;
  private String name;
  private String email;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "specialty_id")
  private Specialty specialty;

  private boolean active;

  public Doctor(String name, String email, Specialty specialty) {
    this.id = UUID.randomUUID();
    this.name = name;
    this.email = email;
    this.specialty = specialty;
    this.active = true;
  }
}
