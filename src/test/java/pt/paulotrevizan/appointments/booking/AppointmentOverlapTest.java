package pt.paulotrevizan.appointments.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import pt.paulotrevizan.appointments.TestcontainersConfiguration;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AppointmentOverlapTest {

  private static final Duration SLOT = Duration.ofMinutes(30);
  private static final Instant START = Instant.parse("2030-01-07T14:00:00Z");

  private final PatientRepository patients;
  private final SpecialtyRepository specialties;
  private final DoctorRepository doctors;
  private final RoomRepository rooms;
  private final AppointmentRepository appointments;
  private final Clock clock;

  private Specialty specialty;

  AppointmentOverlapTest(
      @Autowired PatientRepository patients,
      @Autowired SpecialtyRepository specialties,
      @Autowired DoctorRepository doctors,
      @Autowired RoomRepository rooms,
      @Autowired AppointmentRepository appointments,
      @Autowired Clock clock) {
    this.patients = patients;
    this.specialties = specialties;
    this.doctors = doctors;
    this.rooms = rooms;
    this.appointments = appointments;
    this.clock = clock;
  }

  @BeforeEach
  void createSpecialty() {
    specialty = specialties.save(new Specialty("SPEC_" + UUID.randomUUID(), "Test specialty"));
  }

  @ParameterizedTest(name = "second appointment starts {0} min after the first")
  @ValueSource(longs = {0, 15})
  @DisplayName("I1: a doctor is never double-booked")
  void givenDoctorBooked_whenSameDoctorOverlaps_thenRejectedByDoctorConstraint(long offset) {
    Doctor doctor = newDoctor();
    book(newPatient(), doctor, newRoom(), START);

    assertThatThrownBy(
            () -> book(newPatient(), doctor, newRoom(), START.plus(Duration.ofMinutes(offset))))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("appointment_doctor_no_overlap");
  }

  @ParameterizedTest(name = "second appointment starts {0} min after the first")
  @ValueSource(longs = {0, 15})
  @DisplayName("I2: a room is never double-booked")
  void givenRoomBooked_whenSameRoomOverlaps_thenRejectedByRoomConstraint(long offset) {
    Room room = newRoom();
    book(newPatient(), newDoctor(), room, START);

    assertThatThrownBy(
            () -> book(newPatient(), newDoctor(), room, START.plus(Duration.ofMinutes(offset))))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("appointment_room_no_overlap");
  }

  @ParameterizedTest(name = "second appointment starts {0} min after the first")
  @ValueSource(longs = {0, 15})
  @DisplayName("I5: a patient is never double-booked")
  void givenPatientBooked_whenSamePatientOverlaps_thenRejectedByPatientConstraint(long offset) {
    Patient patient = newPatient();
    book(patient, newDoctor(), newRoom(), START);

    assertThatThrownBy(
            () -> book(patient, newDoctor(), newRoom(), START.plus(Duration.ofMinutes(offset))))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("appointment_patient_no_overlap");
  }

  @Test
  @DisplayName("Back-to-back slots are accepted for the same doctor, room and patient")
  void givenAppointmentEndingAt1430_whenNextStartsAt1430_thenAccepted() {
    Doctor doctor = newDoctor();
    Room room = newRoom();
    Patient patient = newPatient();
    book(patient, doctor, room, START);

    assertThatCode(() -> book(patient, doctor, room, START.plus(SLOT))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Cancelled appointments do not block a slot")
  void givenCancelledAppointment_whenSameDoctorRoomAndPatientBookSlot_thenAccepted() {
    Doctor doctor = newDoctor();
    Room room = newRoom();
    Patient patient = newPatient();
    Appointment first = book(patient, doctor, room, START);
    first.cancel(clock.instant());
    appointments.saveAndFlush(first);

    Appointment second = book(patient, doctor, room, START);

    assertThat(appointments.findById(second.getId())).isPresent();
    assertThat(appointments.findById(first.getId()))
        .get()
        .extracting(Appointment::getStatus)
        .isEqualTo(AppointmentStatus.CANCELLED);
  }

  private Appointment book(Patient patient, Doctor doctor, Room room, Instant startAt) {
    return appointments.saveAndFlush(
        new Appointment(
            patient, specialty, doctor, room, startAt, startAt.plus(SLOT), clock.instant()));
  }

  private Patient newPatient() {
    UUID id = UUID.randomUUID();
    return patients.save(
        new Patient(id, "Patient", id + "@example.com", "+351910000000", clock.instant()));
  }

  private Doctor newDoctor() {
    return doctors.save(new Doctor("Dr. Test", "doctor@example.com", specialty));
  }

  private Room newRoom() {
    return rooms.save(new Room("Room " + UUID.randomUUID()));
  }
}
