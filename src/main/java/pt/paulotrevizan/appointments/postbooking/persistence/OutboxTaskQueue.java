package pt.paulotrevizan.appointments.postbooking.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskType;

/**
 * Claim and complete outbox tasks (HLD 6.2). Every time is taken from the database clock, never
 * from the instances' clocks, and each operation is one short statement.
 */
@Repository
@RequiredArgsConstructor
public class OutboxTaskQueue {

  private static final String CLAIM_DUE =
      """
      WITH due AS (
          SELECT id FROM outbox_task
          WHERE status = 'PENDING'
            AND type IN (:types)
            AND next_attempt_at <= now()
            AND (locked_until IS NULL OR locked_until < now())
          ORDER BY next_attempt_at
          LIMIT :batchSize
          FOR UPDATE SKIP LOCKED)
      UPDATE outbox_task t
         SET locked_until = now() + :leaseMillis * interval '1 millisecond', updated_at = now()
        FROM due, appointment a
       WHERE t.id = due.id AND a.id = t.appointment_id
      RETURNING t.id, t.appointment_id, t.type, t.attempts, a.patient_id,
                a.doctor_id, a.room_id, a.start_at, a.end_at
      """;

  private final JdbcClient jdbc;

  public List<ClaimedTask> claimDue(
      Collection<OutboxTaskType> types, int batchSize, Duration lease) {
    return jdbc.sql(CLAIM_DUE)
        .param("types", types.stream().map(Enum::name).toList())
        .param("batchSize", batchSize)
        .param("leaseMillis", lease.toMillis())
        .query(OutboxTaskQueue::claimedTask)
        .list();
  }

  public void markDone(UUID id) {
    jdbc.sql(
            """
            UPDATE outbox_task
               SET status = 'DONE', locked_until = NULL, last_error = NULL, updated_at = now()
             WHERE id = :id
            """)
        .param("id", id)
        .update();
  }

  public void scheduleRetry(UUID id, Duration delay, String error) {
    jdbc.sql(
            """
            UPDATE outbox_task
               SET attempts = attempts + 1,
                   next_attempt_at = now() + :delayMillis * interval '1 millisecond',
                   locked_until = NULL, last_error = :error, updated_at = now()
             WHERE id = :id
            """)
        .param("id", id)
        .param("delayMillis", delay.toMillis())
        .param("error", error)
        .update();
  }

  public void markFailed(UUID id, String error) {
    jdbc.sql(
            """
            UPDATE outbox_task
               SET status = 'FAILED', attempts = attempts + 1,
                   locked_until = NULL, last_error = :error, updated_at = now()
             WHERE id = :id
            """)
        .param("id", id)
        .param("error", error)
        .update();
  }

  private static ClaimedTask claimedTask(ResultSet row, int rowNumber) throws SQLException {
    return new ClaimedTask(
        row.getObject("id", UUID.class),
        row.getObject("appointment_id", UUID.class),
        OutboxTaskType.valueOf(row.getString("type")),
        row.getInt("attempts"),
        row.getObject("patient_id", UUID.class),
        row.getObject("doctor_id", UUID.class),
        row.getObject("room_id", UUID.class),
        row.getObject("start_at", OffsetDateTime.class).toInstant(),
        row.getObject("end_at", OffsetDateTime.class).toInstant());
  }
}
