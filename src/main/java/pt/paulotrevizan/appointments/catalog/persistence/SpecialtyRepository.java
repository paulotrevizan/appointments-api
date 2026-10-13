package pt.paulotrevizan.appointments.catalog.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import pt.paulotrevizan.appointments.catalog.domain.Specialty;

public interface SpecialtyRepository extends JpaRepository<Specialty, UUID> {

  Optional<Specialty> findByCode(String code);
}
