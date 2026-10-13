package pt.paulotrevizan.appointments.booking;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DoctorRepository extends JpaRepository<Doctor, UUID> {}
