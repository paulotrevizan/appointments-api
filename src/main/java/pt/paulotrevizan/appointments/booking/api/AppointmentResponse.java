package pt.paulotrevizan.appointments.booking.api;

import java.time.Instant;
import java.util.UUID;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.booking.domain.AppointmentStatus;
import pt.paulotrevizan.appointments.catalog.domain.Doctor;
import pt.paulotrevizan.appointments.catalog.domain.Patient;
import pt.paulotrevizan.appointments.catalog.domain.Room;

public record AppointmentResponse(
    UUID id,
    AppointmentStatus status,
    String specialty,
    Instant startAt,
    Instant endAt,
    DoctorSummary doctor,
    RoomSummary room,
    PatientDetails patient,
    Instant createdAt) {

  public record DoctorSummary(UUID id, String name) {}

  public record RoomSummary(UUID id, String name) {}

  public record PatientDetails(UUID id, String name, String email, String phone) {}

  static AppointmentResponse from(Appointment appointment) {
    Doctor doctor = appointment.getDoctor();
    Room room = appointment.getRoom();
    Patient patient = appointment.getPatient();
    return new AppointmentResponse(
        appointment.getId(),
        appointment.getStatus(),
        appointment.getSpecialty().getCode(),
        appointment.getStartAt(),
        appointment.getEndAt(),
        new DoctorSummary(doctor.getId(), doctor.getName()),
        new RoomSummary(room.getId(), room.getName()),
        new PatientDetails(
            patient.getId(), patient.getName(), patient.getEmail(), patient.getPhone()),
        appointment.getCreatedAt());
  }
}
