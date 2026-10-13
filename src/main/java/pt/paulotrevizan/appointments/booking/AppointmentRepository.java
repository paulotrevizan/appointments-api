package pt.paulotrevizan.appointments.booking;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

  @Query(
      """
      select a from Appointment a
        join fetch a.patient
        join fetch a.specialty
        join fetch a.doctor
        join fetch a.room
      where a.patient = :patient
        and a.startAt = :startAt
        and a.status = pt.paulotrevizan.appointments.booking.AppointmentStatus.CONFIRMED
      """)
  Optional<Appointment> findConfirmed(
      @Param("patient") Patient patient, @Param("startAt") Instant startAt);
}
