package pt.paulotrevizan.appointments.booking;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/appointments")
@RequiredArgsConstructor
class BookingController {

  private final BookingService bookingService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Book an appointment",
      description =
          "Assigns a doctor of the requested specialty and a free room. Requires role"
              + " BOOKING_CLIENT. Error bodies are RFC 9457 problem details with a stable `code`.")
  @ApiResponse(responseCode = "201", description = "Booked")
  @ApiResponse(
      responseCode = "400",
      description = "VALIDATION_ERROR, SLOT_IN_PAST or SLOT_NOT_ALIGNED",
      content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid credentials",
      content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
  @ApiResponse(
      responseCode = "403",
      description = "Authenticated without role BOOKING_CLIENT",
      content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
  @ApiResponse(
      responseCode = "404",
      description = "PATIENT_NOT_FOUND or SPECIALTY_NOT_FOUND",
      content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
  @ApiResponse(
      responseCode = "409",
      description = "NO_DOCTOR_AVAILABLE or NO_ROOM_AVAILABLE",
      content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
  AppointmentResponse book(@Valid @RequestBody BookAppointmentRequest request) {
    return AppointmentResponse.from(
        bookingService.book(request.patientId(), request.specialty(), request.startAt()));
  }
}
