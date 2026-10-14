package pt.paulotrevizan.appointments.admin.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pt.paulotrevizan.appointments.admin.application.AppointmentListingService;

@RestController
@RequestMapping("/api/v1/admin/appointments")
@RequiredArgsConstructor
class AppointmentListingController {

  private final AppointmentListingService appointmentListingService;

  @GetMapping
  ResponseEntity<AppointmentPageResponse> list(
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      @RequestParam(required = false) String cursor) {
    return ResponseEntity.ok(
        AppointmentPageResponse.from(appointmentListingService.list(limit, cursor)));
  }
}
