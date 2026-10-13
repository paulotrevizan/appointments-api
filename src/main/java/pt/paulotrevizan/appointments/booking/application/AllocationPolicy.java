package pt.paulotrevizan.appointments.booking.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pt.paulotrevizan.appointments.booking.domain.NoDoctorAvailableException;
import pt.paulotrevizan.appointments.booking.domain.NoRoomAvailableException;
import pt.paulotrevizan.appointments.booking.persistence.AppointmentRepository;
import pt.paulotrevizan.appointments.catalog.domain.Doctor;
import pt.paulotrevizan.appointments.catalog.domain.Room;
import pt.paulotrevizan.appointments.catalog.domain.Specialty;

/** D-009: doctor with the fewest appointments that day (tie: id); room: first free (by id). */
@Service
@RequiredArgsConstructor
class AllocationPolicy {

  private final AppointmentRepository appointments;

  Doctor chooseDoctor(Specialty specialty, Instant startAt, Instant endAt) {
    Instant dayStart = startAt.truncatedTo(ChronoUnit.DAYS);
    return appointments
        .findFreeDoctorsByLoad(
            specialty, startAt, endAt, dayStart, dayStart.plus(1, ChronoUnit.DAYS))
        .stream()
        .findFirst()
        .orElseThrow(() -> new NoDoctorAvailableException(specialty.getCode(), startAt));
  }

  Room chooseRoom(Instant startAt, Instant endAt) {
    return appointments.findFreeRooms(startAt, endAt).stream()
        .findFirst()
        .orElseThrow(() -> new NoRoomAvailableException(startAt));
  }
}
