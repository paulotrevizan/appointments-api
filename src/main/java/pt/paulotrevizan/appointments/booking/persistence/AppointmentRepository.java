package pt.paulotrevizan.appointments.booking.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.catalog.Doctor;
import pt.paulotrevizan.appointments.catalog.Room;
import pt.paulotrevizan.appointments.catalog.Specialty;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

  @Query(
      """
      select d from Doctor d
      where d.specialty = :specialty and d.active = true
        and not exists (
          select 1 from Appointment a
          where a.doctor = d
            and a.status = pt.paulotrevizan.appointments.booking.domain.AppointmentStatus.CONFIRMED
            and a.startAt < :endAt and a.endAt > :startAt)
      order by (
          select count(l) from Appointment l
          where l.doctor = d
            and l.status = pt.paulotrevizan.appointments.booking.domain.AppointmentStatus.CONFIRMED
            and l.startAt >= :dayStart and l.startAt < :dayEnd),
        d.id
      """)
  List<Doctor> findFreeDoctorsByLoad(
      @Param("specialty") Specialty specialty,
      @Param("startAt") Instant startAt,
      @Param("endAt") Instant endAt,
      @Param("dayStart") Instant dayStart,
      @Param("dayEnd") Instant dayEnd);

  @Query(
      """
      select r from Room r
      where r.active = true
        and not exists (
          select 1 from Appointment a
          where a.room = r
            and a.status = pt.paulotrevizan.appointments.booking.domain.AppointmentStatus.CONFIRMED
            and a.startAt < :endAt and a.endAt > :startAt)
      order by r.id
      """)
  List<Room> findFreeRooms(@Param("startAt") Instant startAt, @Param("endAt") Instant endAt);
}
