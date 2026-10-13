package pt.paulotrevizan.appointments.booking;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import pt.paulotrevizan.appointments.common.BaseEntity;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Specialty extends BaseEntity {

  @Id private UUID id;
  private String code;
  private String name;

  public Specialty(String code, String name) {
    this.id = UUID.randomUUID();
    this.code = code;
    this.name = name;
  }
}
