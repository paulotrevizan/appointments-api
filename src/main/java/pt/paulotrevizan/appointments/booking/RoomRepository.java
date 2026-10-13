package pt.paulotrevizan.appointments.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RoomRepository extends JpaRepository<Room, UUID> {

  @Query(
      """
      select r from Room r
      where r.active = true
        and not exists (
          select 1 from Appointment a
          where a.room = r
            and a.status = pt.paulotrevizan.appointments.booking.AppointmentStatus.CONFIRMED
            and a.startAt < :endAt and a.endAt > :startAt)
      order by r.id
      """)
  List<Room> findFree(@Param("startAt") Instant startAt, @Param("endAt") Instant endAt);
}
