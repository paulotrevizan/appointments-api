package pt.paulotrevizan.appointments.booking;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Transaction-scoped locks on a resource at a slot. Slots are fixed-length and on a grid (D-007),
 * so two bookings overlap only when they start at the same instant.
 */
@Component
@RequiredArgsConstructor
class SlotLocks {

  private final JdbcClient jdbc;

  /** Never waits: returns false when another transaction holds the lock. */
  boolean tryLock(String resource, UUID id, Instant startAt) {
    return jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:key, 0))")
        .param("key", resource + ":" + id + ":" + startAt)
        .query(Boolean.class)
        .single();
  }
}
