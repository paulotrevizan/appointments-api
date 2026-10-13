package pt.paulotrevizan.appointments.booking;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PatientRepository extends JpaRepository<Patient, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Patient p where p.id = :id")
  Optional<Patient> findByIdForUpdate(@Param("id") UUID id);
}
