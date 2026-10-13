package pt.paulotrevizan.appointments.postbooking;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.booking.persistence.AppointmentRepository;
import pt.paulotrevizan.appointments.catalog.domain.Doctor;
import pt.paulotrevizan.appointments.catalog.domain.Patient;
import pt.paulotrevizan.appointments.catalog.domain.Room;
import pt.paulotrevizan.appointments.catalog.domain.Specialty;
import pt.paulotrevizan.appointments.catalog.persistence.DoctorRepository;
import pt.paulotrevizan.appointments.catalog.persistence.PatientRepository;
import pt.paulotrevizan.appointments.catalog.persistence.RoomRepository;
import pt.paulotrevizan.appointments.catalog.persistence.SpecialtyRepository;
import pt.paulotrevizan.appointments.postbooking.application.PostBookingTasks;

/** Creates the data post-booking tests need; every call uses new, unique reference data. */
@RequiredArgsConstructor
public class PostBookingFixtures {

  public record ReferenceData(Patient patient, Specialty specialty, Doctor doctor, Room room) {}

  private final PatientRepository patients;
  private final SpecialtyRepository specialties;
  private final DoctorRepository doctors;
  private final RoomRepository rooms;
  private final AppointmentRepository appointments;
  private final PostBookingTasks postBookingTasks;
  private final Clock clock;

  public ReferenceData referenceData() {
    Specialty specialty =
        specialties.save(new Specialty("SPEC_" + UUID.randomUUID(), "Test specialty"));
    UUID patientId = UUID.randomUUID();
    return new ReferenceData(
        patients.save(
            new Patient(
                patientId,
                "Ana Souza",
                patientId + "@example.com",
                "+351912345678",
                clock.instant())),
        specialty,
        doctors.save(new Doctor("Dr. Test", "doctor@example.com", specialty)),
        rooms.save(new Room("Room " + UUID.randomUUID())));
  }

  /** An appointment with its three PENDING outbox tasks, as a booking creates it. */
  public Appointment bookedAppointment(Instant startAt) {
    ReferenceData data = referenceData();
    Appointment appointment =
        appointments.saveAndFlush(
            new Appointment(
                data.patient(),
                data.specialty(),
                data.doctor(),
                data.room(),
                startAt,
                clock.instant()));
    postBookingTasks.scheduleFor(appointment);
    return appointment;
  }
}
