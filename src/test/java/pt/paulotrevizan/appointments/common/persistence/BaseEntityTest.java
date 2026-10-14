package pt.paulotrevizan.appointments.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import pt.paulotrevizan.appointments.TestcontainersConfiguration;
import pt.paulotrevizan.appointments.catalog.domain.Specialty;
import pt.paulotrevizan.appointments.catalog.persistence.SpecialtyRepository;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BaseEntityTest {

  private final SpecialtyRepository specialties;
  private final Statistics statistics;

  BaseEntityTest(
      @Autowired SpecialtyRepository specialties,
      @Autowired EntityManagerFactory entityManagerFactory) {
    this.specialties = specialties;
    this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
  }

  @Test
  @DisplayName(
      "D-025: saving a new entity with an application-assigned id is one INSERT, no SELECT")
  void givenNewEntityWithAssignedId_whenSaved_thenSingleInsertWithoutSelect() {
    Specialty specialty = new Specialty("SPEC_" + UUID.randomUUID(), "Test specialty");
    statistics.clear();

    specialties.save(specialty);

    assertThat(statistics.getEntityInsertCount()).isOne();
    assertThat(statistics.getPrepareStatementCount()).isOne();
    assertThat(specialties.findById(specialty.getId())).isPresent();
  }
}
