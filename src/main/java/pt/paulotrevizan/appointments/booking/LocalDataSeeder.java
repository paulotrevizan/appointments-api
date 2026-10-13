package pt.paulotrevizan.appointments.booking;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** D-024: sample reference data for local runs. Never active in production or in tests. */
@Slf4j
@Component
@Profile("local")
@EnableConfigurationProperties(SeedProperties.class)
@RequiredArgsConstructor
class LocalDataSeeder implements ApplicationRunner {

  private record SpecialtySeed(String code, String name) {}

  private static final List<SpecialtySeed> SPECIALTIES =
      List.of(
          new SpecialtySeed("CARDIOLOGY", "Cardiology"),
          new SpecialtySeed("DERMATOLOGY", "Dermatology"),
          new SpecialtySeed("GENERAL_PRACTICE", "General Practice"),
          new SpecialtySeed("PEDIATRICS", "Pediatrics"),
          new SpecialtySeed("ORTHOPEDICS", "Orthopedics"),
          new SpecialtySeed("NEUROLOGY", "Neurology"));

  private static final List<UUID> DEMO_PATIENT_IDS =
      List.of(
          UUID.fromString("00000000-0000-0000-0000-000000000001"),
          UUID.fromString("00000000-0000-0000-0000-000000000002"),
          UUID.fromString("00000000-0000-0000-0000-000000000003"));

  private final SeedProperties properties;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;
  private final PatientRepository patients;
  private final SpecialtyRepository specialties;
  private final DoctorRepository doctors;
  private final RoomRepository rooms;

  @Override
  public void run(ApplicationArguments args) {
    seed();
  }

  void seed() {
    transactionTemplate.executeWithoutResult(status -> seedIfEmpty());
  }

  private void seedIfEmpty() {
    if (!referenceTablesAreEmpty()) {
      log.info("Local seed skipped: reference tables already contain data");
      return;
    }
    Faker faker = new Faker(Locale.ENGLISH);
    Instant now = clock.instant();

    List<Specialty> savedSpecialties =
        specialties.saveAll(
            SPECIALTIES.stream().map(s -> new Specialty(s.code(), s.name())).toList());
    int doctorCount = 0;
    for (Specialty specialty : savedSpecialties) {
      int count =
          ThreadLocalRandom.current()
              .nextInt(
                  properties.minDoctorsPerSpecialty(), properties.maxDoctorsPerSpecialty() + 1);
      for (int i = 1; i <= count; i++) {
        doctors.save(newDoctor(faker, specialty, doctorCount++));
      }
    }
    for (int i = 1; i <= properties.rooms(); i++) {
      rooms.save(new Room("Room " + i));
    }
    for (int i = 0; i < DEMO_PATIENT_IDS.size(); i++) {
      int number = i + 1;
      patients.save(
          new Patient(
              DEMO_PATIENT_IDS.get(i),
              "Demo Patient " + number,
              "demo.patient" + number + "@example.com",
              "+35191000000" + number,
              now));
    }
    for (int i = 1; i <= properties.patients(); i++) {
      patients.save(newPatient(faker, i, now));
    }
    log.info(
        "Local seed done: {} specialties, {} doctors, {} rooms, {} patients",
        savedSpecialties.size(),
        doctorCount,
        properties.rooms(),
        properties.patients() + DEMO_PATIENT_IDS.size());
  }

  private boolean referenceTablesAreEmpty() {
    return patients.count() == 0
        && specialties.count() == 0
        && doctors.count() == 0
        && rooms.count() == 0;
  }

  private Doctor newDoctor(Faker faker, Specialty specialty, int index) {
    String first = faker.name().firstName();
    String last = faker.name().lastName();
    return new Doctor("Dr. " + first + " " + last, emailOf("dr", first, last, index), specialty);
  }

  private Patient newPatient(Faker faker, int index, Instant now) {
    String first = faker.name().firstName();
    String last = faker.name().lastName();
    return new Patient(
        UUID.randomUUID(),
        first + " " + last,
        emailOf("patient", first, last, index),
        "+3519" + faker.number().digits(8),
        now);
  }

  // The index keeps emails unique (patient.email has a unique constraint).
  private static String emailOf(String prefix, String first, String last, int index) {
    String name = (first + "." + last).toLowerCase(Locale.ROOT).replaceAll("[^a-z.]", "");
    return "%s.%s.%d@example.com".formatted(prefix, name, index);
  }
}
