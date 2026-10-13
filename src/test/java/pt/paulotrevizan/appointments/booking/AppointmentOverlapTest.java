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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import pt.paulotrevizan.appointments.TestcontainersConfiguration;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.booking.domain.AppointmentStatus;
import pt.paulotrevizan.appointments.booking.persistence.AppointmentRepository;
import pt.paulotrevizan.appointments.catalog.domain.Doctor;
import pt.paulotrevizan.appointments.catalog.domain.Patient;
import pt.paulotrevizan.appointments.catalog.domain.Room;
import pt.paulotrevizan.appointments.catalog.domain.Specialty;
import pt.paulotrevizan.appointments.catalog.persistence.DoctorRepository;
import pt.paulotrevizan.appointments.catalog.persistence.PatientRepository;
import pt.paulotrevizan.appointments.catalog.persistence.RoomRepository;
import pt.paulotrevizan.appointments.catalog.persistence.SpecialtyRepository;

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
  private final JdbcClient jdbc;

  private Specialty specialty;

  AppointmentOverlapTest(
      @Autowired PatientRepository patients,
      @Autowired SpecialtyRepository specialties,
      @Autowired DoctorRepository doctors,
      @Autowired RoomRepository rooms,
      @Autowired AppointmentRepository appointments,
      @Autowired Clock clock,
      @Autowired JdbcClient jdbc) {
    this.patients = patients;
    this.specialties = specialties;
    this.doctors = doctors;
    this.rooms = rooms;
    this.appointments = appointments;
    this.clock = clock;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void createSpecialty() {
    specialty = specialties.save(new Specialty("SPEC_" + UUID.randomUUID(), "Test specialty"));
  }

  @Test
  @DisplayName("I1: a doctor is never double-booked")
  void givenDoctorBooked_whenSameDoctorSameSlot_thenRejectedByDoctorConstraint() {
    Doctor doctor = newDoctor();
    book(newPatient(), doctor, newRoom(), START);

    assertThatThrownBy(() -> book(newPatient(), doctor, newRoom(), START))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("appointment_doctor_no_overlap");
  }

  @Test
  @DisplayName("I2: a room is never double-booked")
  void givenRoomBooked_whenSameRoomSameSlot_thenRejectedByRoomConstraint() {
    Room room = newRoom();
    book(newPatient(), newDoctor(), room, START);

    assertThatThrownBy(() -> book(newPatient(), newDoctor(), room, START))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("appointment_room_no_overlap");
  }

  @Test
  @DisplayName("I5: a patient is never double-booked")
  void givenPatientBooked_whenSamePatientSameSlot_thenRejectedByPatientConstraint() {
    Patient patient = newPatient();
    book(patient, newDoctor(), newRoom(), START);

    assertThatThrownBy(() -> book(patient, newDoctor(), newRoom(), START))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("appointment_patient_no_overlap");
  }

  @Test
  @DisplayName("D-002: a start off the 30-minute grid is rejected by the database")
  void givenStartOffGrid_whenInserted_thenRejectedByGridCheck() {
    assertThatThrownBy(
            () -> book(newPatient(), newDoctor(), newRoom(), START.plus(Duration.ofMinutes(15))))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("appointment_grid_check");
  }

  @Test
  @DisplayName("D-002: an appointment that is not 30 minutes long is rejected by the database")
  void givenAppointment_whenDurationChanged_thenRejectedByDurationCheck() {
    Appointment appointment = book(newPatient(), newDoctor(), newRoom(), START);

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "UPDATE appointment SET end_at = end_at + interval '15 minutes' WHERE id = :id")
                    .param("id", appointment.getId())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("appointment_fixed_duration_check");
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
        new Appointment(patient, specialty, doctor, room, startAt, clock.instant()));
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
