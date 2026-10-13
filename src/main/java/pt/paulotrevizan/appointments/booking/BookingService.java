package pt.paulotrevizan.appointments.booking;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

  private final SlotPolicy slotPolicy;
  private final SpecialtyRepository specialties;
  private final AppointmentBooker booker;

  public BookingResult book(UUID patientId, String specialtyCode, Instant startAt) {
    slotPolicy.validate(startAt);
    Specialty specialty =
        specialties
            .findByCode(specialtyCode)
            .orElseThrow(() -> new SpecialtyNotFoundException(specialtyCode));

    BookingResult result = booker.book(patientId, specialty, startAt);
    log.info(
        "Appointment {} {}",
        result.appointment().getId(),
        result.created() ? "booked" : "already booked");
    return result;
  }
}
