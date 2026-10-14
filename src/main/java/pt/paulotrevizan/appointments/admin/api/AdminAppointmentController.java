package pt.paulotrevizan.appointments.admin.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pt.paulotrevizan.appointments.admin.application.AdminAppointmentService;

@RestController
@RequestMapping("/api/v1/admin/appointments")
@RequiredArgsConstructor
class AdminAppointmentController {

  private final AdminAppointmentService adminAppointmentService;

  @GetMapping
  ResponseEntity<AdminAppointmentPageResponse> list(
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      @RequestParam(required = false) String cursor) {
    return ResponseEntity.ok(
        AdminAppointmentPageResponse.from(adminAppointmentService.list(limit, cursor)));
  }
}
