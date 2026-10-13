package pt.paulotrevizan.appointments.booking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import pt.paulotrevizan.appointments.TestcontainersConfiguration;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LocalDataSeederInactiveTest {

  private final ApplicationContext context;
  private final JdbcClient jdbc;

  LocalDataSeederInactiveTest(@Autowired ApplicationContext context, @Autowired JdbcClient jdbc) {
    this.context = context;
    this.jdbc = jdbc;
  }

  @Test
  @DisplayName("D-024: the seeder is not active outside the local profile")
  void givenTestProfile_whenApplicationStarts_thenSeederIsAbsentAndDemoPatientsDoNotExist() {
    assertThat(context.getBeanNamesForType(LocalDataSeeder.class)).isEmpty();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM patient WHERE id::text LIKE '00000000-0000-0000-0000-00000000000_'")
                .query(Integer.class)
                .single())
        .isZero();
  }
}
