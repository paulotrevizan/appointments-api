package pt.paulotrevizan.appointments.catalog;

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
