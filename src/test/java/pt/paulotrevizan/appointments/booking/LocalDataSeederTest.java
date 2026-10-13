package pt.paulotrevizan.appointments.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import pt.paulotrevizan.appointments.TestcontainersConfiguration;

@SpringBootTest(
    properties = {
      "appointments.seed.min-doctors-per-specialty=2",
      "appointments.seed.max-doctors-per-specialty=4",
      "appointments.seed.rooms=5",
      "appointments.seed.patients=7"
    })
@ActiveProfiles({"test", "local"})
@Import(TestcontainersConfiguration.class)
class LocalDataSeederTest {

  private static final int SPECIALTIES = 6;
  private static final int DEMO_PATIENTS = 3;

  private final LocalDataSeeder seeder;
  private final JdbcClient jdbc;

  LocalDataSeederTest(@Autowired LocalDataSeeder seeder, @Autowired JdbcClient jdbc) {
    this.seeder = seeder;
    this.jdbc = jdbc;
  }

  @Test
  @DisplayName("D-024: local profile on an empty database creates the configured reference data")
  void givenEmptyDatabase_whenApplicationStartsWithLocalProfile_thenReferenceDataExists() {
    assertThat(count("specialty")).isEqualTo(SPECIALTIES);
    assertThat(count("room")).isEqualTo(5);
    assertThat(count("patient")).isEqualTo(7 + DEMO_PATIENTS);
    assertThat(
            jdbc.sql("SELECT count(*) FROM doctor GROUP BY specialty_id")
                .query(Integer.class)
                .list())
        .hasSize(SPECIALTIES)
        .allSatisfy(perSpecialty -> assertThat(perSpecialty).isBetween(2, 4));
  }

  @Test
  @DisplayName("D-024: the three demo patients have fixed ids")
  void givenSeededDatabase_whenLookingUpDemoIds_thenThreePatientsExist() {
    for (int number = 1; number <= DEMO_PATIENTS; number++) {
      UUID id = UUID.fromString("00000000-0000-0000-0000-00000000000" + number);
      assertThat(
              jdbc.sql("SELECT count(*) FROM patient WHERE id = :id")
                  .param("id", id)
                  .query(Integer.class)
                  .single())
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName("D-024: running the seeder again duplicates nothing")
  void givenSeededDatabase_whenSeederRunsAgain_thenNothingIsDuplicated() {
    int patientsBefore = count("patient");
    int doctorsBefore = count("doctor");

    seeder.seed();

    assertThat(count("specialty")).isEqualTo(SPECIALTIES);
    assertThat(count("room")).isEqualTo(5);
    assertThat(count("patient")).isEqualTo(patientsBefore);
    assertThat(count("doctor")).isEqualTo(doctorsBefore);
  }

  private int count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
  }
}
