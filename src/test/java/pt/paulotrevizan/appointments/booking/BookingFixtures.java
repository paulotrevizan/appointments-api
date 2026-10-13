package pt.paulotrevizan.appointments.booking;

import java.time.Clock;
import java.util.UUID;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import pt.paulotrevizan.appointments.catalog.domain.Doctor;
import pt.paulotrevizan.appointments.catalog.domain.Patient;
import pt.paulotrevizan.appointments.catalog.domain.Room;
import pt.paulotrevizan.appointments.catalog.domain.Specialty;
import pt.paulotrevizan.appointments.catalog.persistence.DoctorRepository;
import pt.paulotrevizan.appointments.catalog.persistence.PatientRepository;
import pt.paulotrevizan.appointments.catalog.persistence.RoomRepository;
import pt.paulotrevizan.appointments.catalog.persistence.SpecialtyRepository;

/** Creates the reference data a test needs, with unique names so tests never collide. */
@RequiredArgsConstructor
class BookingFixtures {

  private final PatientRepository patients;
  private final SpecialtyRepository specialties;
  private final DoctorRepository doctors;
  private final RoomRepository rooms;
  private final Clock clock;

  Specialty specialty() {
    return specialties.save(new Specialty("SPEC_" + UUID.randomUUID(), "Test specialty"));
  }

  void doctors(Specialty specialty, int count) {
    IntStream.range(0, count)
        .forEach(i -> doctors.save(new Doctor("Dr. Test", "doctor@example.com", specialty)));
  }

  void rooms(int count) {
    IntStream.range(0, count).forEach(i -> rooms.save(new Room("Room " + UUID.randomUUID())));
  }

  Patient patient() {
    UUID id = UUID.randomUUID();
    return patients.save(
        new Patient(id, "Ana Souza", id + "@example.com", "+351912345678", clock.instant()));
  }
}
