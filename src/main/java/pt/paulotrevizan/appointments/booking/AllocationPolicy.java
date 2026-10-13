package pt.paulotrevizan.appointments.booking;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** D-009: doctor with the fewest appointments that day (tie: id); room: first free (by id). */
@Service
@RequiredArgsConstructor
class AllocationPolicy {

  private final DoctorRepository doctors;
  private final RoomRepository rooms;
  private final SlotLocks locks;
  private final BookingProperties properties;

  Allocation allocate(Specialty specialty, Instant startAt) {
    Instant endAt = startAt.plus(properties.slotDuration());
    Instant dayStart = startAt.truncatedTo(ChronoUnit.DAYS);
    List<Doctor> freeDoctors =
        doctors.findFreeByLoad(
            specialty, startAt, endAt, dayStart, dayStart.plus(1, ChronoUnit.DAYS));
    Doctor doctor =
        claimFirst(freeDoctors, "doctor", Doctor::getId, startAt)
            .orElseThrow(() -> new NoDoctorAvailableException(specialty.getCode(), startAt));
    Room room =
        claimFirst(rooms.findFree(startAt, endAt), "room", Room::getId, startAt)
            .orElseThrow(() -> new NoRoomAvailableException(startAt));
    return new Allocation(doctor, room, endAt);
  }

  private <T> java.util.Optional<T> claimFirst(
      List<T> candidates, String resource, Function<T, UUID> id, Instant startAt) {
    return candidates.stream()
        .filter(candidate -> locks.tryLock(resource, id.apply(candidate), startAt))
        .findFirst();
  }
}
