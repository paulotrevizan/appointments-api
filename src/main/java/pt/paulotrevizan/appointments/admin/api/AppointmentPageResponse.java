package pt.paulotrevizan.appointments.admin.api;

import java.util.List;
import java.util.Map;
import pt.paulotrevizan.appointments.admin.application.AppointmentPage;

/** {@code nextCursor} is null on the last page (HLD 7.2). */
public record AppointmentPageResponse(List<AppointmentListItem> items, String nextCursor) {

  static AppointmentPageResponse from(AppointmentPage page) {
    return new AppointmentPageResponse(
        page.appointments().stream()
            .map(
                appointment ->
                    AppointmentListItem.from(
                        appointment,
                        page.postBookingStatuses().getOrDefault(appointment.getId(), Map.of())))
            .toList(),
        page.nextCursor());
  }
}
