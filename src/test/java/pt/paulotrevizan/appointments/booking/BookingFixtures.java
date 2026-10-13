package pt.paulotrevizan.appointments.booking;

import java.time.Clock;
import java.util.UUID;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;

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

  Doctor doctor(Specialty specialty) {
    return doctors.save(new Doctor("Dr. Test", "doctor@example.com", specialty));
  }

  void doctors(Specialty specialty, int count) {
    IntStream.range(0, count).forEach(i -> doctor(specialty));
  }

  Room room() {
    return rooms.save(new Room("Room " + UUID.randomUUID()));
  }

  void rooms(int count) {
    IntStream.range(0, count).forEach(i -> room());
  }

  Patient patient() {
    UUID id = UUID.randomUUID();
    return patients.save(
        new Patient(id, "Ana Souza", id + "@example.com", "+351912345678", clock.instant()));
  }
}
