package pt.paulotrevizan.appointments.booking;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AppointmentRepository extends JpaRepository<Appointment, UUID> {}
