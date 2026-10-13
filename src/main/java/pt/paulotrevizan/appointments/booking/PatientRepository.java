package pt.paulotrevizan.appointments.booking;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PatientRepository extends JpaRepository<Patient, UUID> {}
