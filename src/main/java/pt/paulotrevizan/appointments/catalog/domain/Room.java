package pt.paulotrevizan.appointments.catalog.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import pt.paulotrevizan.appointments.common.persistence.BaseEntity;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Room extends BaseEntity {

  @Id private UUID id;
  private String name;
  private boolean active;

  public Room(String name) {
    this.id = UUID.randomUUID();
    this.name = name;
    this.active = true;
  }
}
