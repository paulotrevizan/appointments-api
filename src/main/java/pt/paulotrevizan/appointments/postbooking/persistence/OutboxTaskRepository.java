package pt.paulotrevizan.appointments.postbooking.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTask;

public interface OutboxTaskRepository extends JpaRepository<OutboxTask, UUID> {

  /**
   * Due tasks, locked until the caller's transaction ends; rows locked by another instance are
   * skipped (D-004). Must run inside a read-write transaction.
   */
  @Query(
      nativeQuery = true,
      value =
          """
          SELECT * FROM outbox_task
          WHERE status = 'PENDING' AND type IN (:types) AND next_attempt_at <= :now
          ORDER BY next_attempt_at
          LIMIT :limit
          FOR UPDATE SKIP LOCKED
          """)
  List<OutboxTask> findDueForUpdate(
      @Param("types") Collection<String> types,
      @Param("now") Instant now,
      @Param("limit") int limit);
}
