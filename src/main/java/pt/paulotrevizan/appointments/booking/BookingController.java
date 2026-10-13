package pt.paulotrevizan.appointments.booking;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/appointments")
@RequiredArgsConstructor
class BookingController {

  private final BookingService bookingService;

  @PostMapping
  ResponseEntity<AppointmentResponse> book(@Valid @RequestBody BookAppointmentRequest request) {
    Appointment appointment =
        bookingService.book(request.patientId(), request.specialty(), request.startAt());
    return ResponseEntity.status(HttpStatus.CREATED).body(AppointmentResponse.from(appointment));
  }
}
