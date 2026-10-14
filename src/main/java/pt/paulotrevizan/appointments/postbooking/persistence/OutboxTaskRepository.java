package pt.paulotrevizan.appointments.postbooking.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTask;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskStatus;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskType;

/**
 * Times come from the database clock, never from the instances (HLD 6.2). The updates after a claim
 * match on {@code locked_until}, so an instance whose lease expired changes nothing.
 */
public interface OutboxTaskRepository extends JpaRepository<OutboxTask, UUID> {

  List<OutboxTask> findByAppointmentIdIn(Collection<UUID> appointmentIds);

  long countByTypeAndStatus(OutboxTaskType type, OutboxTaskStatus status);

  /** How long the oldest PENDING task has waited, in seconds; 0 when nothing is pending. */
  @Query(
      nativeQuery = true,
      value =
          """
          SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0)
          FROM outbox_task
          WHERE status = 'PENDING'
          """)
  double oldestPendingAgeSeconds();

  /** Leases up to {@code batchSize} due tasks, skipping rows another instance is claiming. */
  @Transactional
  @Query(
      nativeQuery = true,
      value =
          """
          WITH due AS (
            SELECT id FROM outbox_task
            WHERE status = 'PENDING'
              AND next_attempt_at <= now()
              AND (locked_until IS NULL OR locked_until < now())
            ORDER BY next_attempt_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED)
          UPDATE outbox_task t
          SET locked_until = now() + make_interval(secs => :leaseSeconds), updated_at = now()
          FROM due
          WHERE t.id = due.id
          RETURNING t.*
          """)
  List<OutboxTask> claimDue(
      @Param("batchSize") int batchSize, @Param("leaseSeconds") double leaseSeconds);

  @Transactional
  @Modifying
  @Query(
      nativeQuery = true,
      value =
          """
          UPDATE outbox_task
          SET status = 'DONE', attempts = attempts + 1, locked_until = NULL, last_error = NULL,
              updated_at = now()
          WHERE id = :id AND locked_until = :lockedUntil
          """)
  int markDone(@Param("id") UUID id, @Param("lockedUntil") Instant lockedUntil);

  @Transactional
  @Modifying
  @Query(
      nativeQuery = true,
      value =
          """
          UPDATE outbox_task
          SET attempts = attempts + 1,
              next_attempt_at = now() + make_interval(secs => :delaySeconds),
              locked_until = NULL, last_error = :error, updated_at = now()
          WHERE id = :id AND locked_until = :lockedUntil
          """)
  int scheduleRetry(
      @Param("id") UUID id,
      @Param("lockedUntil") Instant lockedUntil,
      @Param("delaySeconds") double delaySeconds,
      @Param("error") String error);

  @Transactional
  @Modifying
  @Query(
      nativeQuery = true,
      value =
          """
          UPDATE outbox_task
          SET status = 'FAILED', attempts = attempts + 1, locked_until = NULL,
              last_error = :error, updated_at = now()
          WHERE id = :id AND locked_until = :lockedUntil
          """)
  int markFailed(
      @Param("id") UUID id,
      @Param("lockedUntil") Instant lockedUntil,
      @Param("error") String error);
}
