package pt.paulotrevizan.appointments.catalog.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import pt.paulotrevizan.appointments.catalog.domain.Doctor;

public interface DoctorRepository extends JpaRepository<Doctor, UUID> {}
