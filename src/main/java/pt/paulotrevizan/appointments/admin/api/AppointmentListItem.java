package pt.paulotrevizan.appointments.admin.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import pt.paulotrevizan.appointments.booking.api.AppointmentResponse.DoctorSummary;
import pt.paulotrevizan.appointments.booking.api.AppointmentResponse.RoomSummary;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.booking.domain.AppointmentStatus;
import pt.paulotrevizan.appointments.catalog.domain.Doctor;
import pt.paulotrevizan.appointments.catalog.domain.Patient;
import pt.paulotrevizan.appointments.catalog.domain.Room;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskStatus;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskType;

/**
 * One item of the admin listing (HLD 7.2). Doctor and room are the booking response's types: the
 * same concept, so one schema in the OpenAPI document (springdoc names schemas by simple name).
 */
public record AppointmentListItem(
    UUID id,
    AppointmentStatus status,
    String specialty,
    Instant startAt,
    Instant endAt,
    DoctorSummary doctor,
    RoomSummary room,
    PatientSummary patient,
    PostBooking postBooking) {

  public record PatientSummary(UUID id, String name, String email) {}

  public record PostBooking(
      OutboxTaskStatus doctorCalendar, OutboxTaskStatus roomReservation, OutboxTaskStatus email) {}

  static AppointmentListItem from(
      Appointment appointment, Map<OutboxTaskType, OutboxTaskStatus> postBooking) {
    Doctor doctor = appointment.getDoctor();
    Room room = appointment.getRoom();
    Patient patient = appointment.getPatient();
    return new AppointmentListItem(
        appointment.getId(),
        appointment.getStatus(),
        appointment.getSpecialty().getCode(),
        appointment.getStartAt(),
        appointment.getEndAt(),
        new DoctorSummary(doctor.getId(), doctor.getName()),
        new RoomSummary(room.getId(), room.getName()),
        new PatientSummary(patient.getId(), patient.getName(), patient.getEmail()),
        new PostBooking(
            postBooking.get(OutboxTaskType.DOCTOR_CALENDAR),
            postBooking.get(OutboxTaskType.ROOM_RESERVATION),
            postBooking.get(OutboxTaskType.EMAIL)));
  }
}
