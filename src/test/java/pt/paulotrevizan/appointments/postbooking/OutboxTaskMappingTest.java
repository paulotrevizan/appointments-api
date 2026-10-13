package pt.paulotrevizan.appointments.postbooking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import pt.paulotrevizan.appointments.TestcontainersConfiguration;
import pt.paulotrevizan.appointments.booking.Appointment;
import pt.paulotrevizan.appointments.booking.Doctor;
import pt.paulotrevizan.appointments.booking.Patient;
import pt.paulotrevizan.appointments.booking.Room;
import pt.paulotrevizan.appointments.booking.Specialty;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OutboxTaskMappingTest {

  private final EntityManager entityManager;
  private final TransactionTemplate transactions;
  private final Environment environment;
  private final Clock clock;

  OutboxTaskMappingTest(
      @Autowired EntityManager entityManager,
      @Autowired TransactionTemplate transactions,
      @Autowired Environment environment,
      @Autowired Clock clock) {
    this.entityManager = entityManager;
    this.transactions = transactions;
    this.environment = environment;
    this.clock = clock;
  }

  @Test
  @DisplayName("Hibernate validates all entities against the Flyway schema")
  void givenMigratedSchema_whenContextStarts_thenHibernateValidationIsOn() {
    assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
  }

  @Test
  @DisplayName("Outbox tasks start PENDING with timestamps from the database clock")
  void givenAppointment_whenThreeTasksPersisted_thenPendingWithDatabaseTimestamps() {
    UUID appointmentId = persistAppointmentWithTasks();

    List<OutboxTask> tasks = loadTasks(appointmentId);

    assertThat(tasks)
        .extracting(OutboxTask::getType)
        .containsExactlyInAnyOrder(OutboxTaskType.values());
    assertThat(tasks)
        .allSatisfy(
            task -> {
              assertThat(task.getStatus()).isEqualTo(OutboxTaskStatus.PENDING);
              assertThat(task.getAttempts()).isZero();
              assertThat(task.getNextAttemptAt()).isNotNull();
              assertThat(task.getCreatedAt()).isNotNull();
              assertThat(task.getUpdatedAt()).isNotNull();
            });
  }

  @Test
  @DisplayName("An appointment has at most one outbox task of each type")
  void givenTaskOfType_whenSameTypePersistedAgain_thenRejectedByUniqueConstraint() {
    UUID appointmentId = persistAppointmentWithTasks();

    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      Appointment appointment =
                          entityManager.getReference(Appointment.class, appointmentId);
                      entityManager.persist(new OutboxTask(appointment, OutboxTaskType.EMAIL));
                      entityManager.flush();
                    }))
        .rootCause()
        .hasMessageContaining("outbox_task_appointment_type_unique");
  }

  private UUID persistAppointmentWithTasks() {
    return transactions.execute(
        status -> {
          Instant now = clock.instant();
          UUID patientId = UUID.randomUUID();
          Patient patient =
              new Patient(patientId, "Patient", patientId + "@example.com", "+351910000000", now);
          Specialty specialty = new Specialty("SPEC_" + UUID.randomUUID(), "Test specialty");
          Doctor doctor = new Doctor("Dr. Test", "doctor@example.com", specialty);
          Room room = new Room("Room " + UUID.randomUUID());
          Appointment appointment =
              new Appointment(
                  patient,
                  specialty,
                  doctor,
                  room,
                  Instant.parse("2030-01-07T14:00:00Z"),
                  Instant.parse("2030-01-07T14:30:00Z"),
                  now);
          List.of(patient, specialty, doctor, room, appointment).forEach(entityManager::persist);
          for (OutboxTaskType type : OutboxTaskType.values()) {
            entityManager.persist(new OutboxTask(appointment, type));
          }
          return appointment.getId();
        });
  }

  private List<OutboxTask> loadTasks(UUID appointmentId) {
    return transactions.execute(
        status ->
            entityManager
                .createQuery(
                    "select t from OutboxTask t where t.appointment.id = :appointmentId",
                    OutboxTask.class)
                .setParameter("appointmentId", appointmentId)
                .getResultList());
  }
}
