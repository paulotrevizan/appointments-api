package pt.paulotrevizan.appointments.booking;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** D-009: doctor with the fewest appointments that day (tie: id); room: first free (by id). */
@Service
@RequiredArgsConstructor
class AllocationPolicy {

  private final DoctorRepository doctors;
  private final RoomRepository rooms;

  Doctor chooseDoctor(Specialty specialty, Instant startAt, Instant endAt) {
    Instant dayStart = startAt.truncatedTo(ChronoUnit.DAYS);
    return doctors
        .findFreeByLoad(specialty, startAt, endAt, dayStart, dayStart.plus(1, ChronoUnit.DAYS))
        .stream()
        .findFirst()
        .orElseThrow(() -> new NoDoctorAvailableException(specialty.getCode(), startAt));
  }

  Room chooseRoom(Instant startAt, Instant endAt) {
    return rooms.findFree(startAt, endAt).stream()
        .findFirst()
        .orElseThrow(() -> new NoRoomAvailableException(startAt));
  }
}
