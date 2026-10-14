package pt.paulotrevizan.appointments.admin.api;

import java.util.List;
import java.util.Map;
import pt.paulotrevizan.appointments.admin.application.AppointmentPage;

/** {@code nextCursor} is null on the last page (HLD 7.2). */
public record AdminAppointmentPageResponse(
    List<AdminAppointmentResponse> items, String nextCursor) {

  static AdminAppointmentPageResponse from(AppointmentPage page) {
    return new AdminAppointmentPageResponse(
        page.appointments().stream()
            .map(
                appointment ->
                    AdminAppointmentResponse.from(
                        appointment,
                        page.postBookingStatuses().getOrDefault(appointment.getId(), Map.of())))
            .toList(),
        page.nextCursor());
  }
}
