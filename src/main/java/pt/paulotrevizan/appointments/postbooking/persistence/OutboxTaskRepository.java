package pt.paulotrevizan.appointments.postbooking.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTask;

public interface OutboxTaskRepository extends JpaRepository<OutboxTask, UUID> {}
