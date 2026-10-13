package pt.paulotrevizan.appointments.postbooking;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OutboxTaskRepository extends JpaRepository<OutboxTask, UUID> {}
