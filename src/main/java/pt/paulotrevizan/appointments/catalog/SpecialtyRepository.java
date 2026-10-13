package pt.paulotrevizan.appointments.catalog;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpecialtyRepository extends JpaRepository<Specialty, UUID> {

  Optional<Specialty> findByCode(String code);
}
