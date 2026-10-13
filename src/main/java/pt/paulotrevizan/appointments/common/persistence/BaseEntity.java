package pt.paulotrevizan.appointments.common.persistence;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Ids are assigned by the application, so Spring Data cannot tell a new entity from a detached one
 * by looking at the id. Without this, {@code save} would issue a {@code SELECT} and then merge.
 */
@MappedSuperclass
public abstract class BaseEntity implements Persistable<UUID> {

  @Transient private boolean isNew = true;

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostPersist
  @PostLoad
  void markNotNew() {
    isNew = false;
  }
}
