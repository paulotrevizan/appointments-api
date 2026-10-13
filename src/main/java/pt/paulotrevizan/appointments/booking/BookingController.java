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
    BookingResult result =
        bookingService.book(request.patientId(), request.specialty(), request.startAt());
    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status).body(AppointmentResponse.from(result.appointment()));
  }
}
