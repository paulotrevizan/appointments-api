package pt.paulotrevizan.appointments.admin.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskStatus;
import pt.paulotrevizan.appointments.postbooking.domain.OutboxTaskType;

/** {@code nextCursor} is null on the last page. */
public record AppointmentPage(
    List<Appointment> appointments,
    Map<UUID, Map<OutboxTaskType, OutboxTaskStatus>> postBookingStatuses,
    String nextCursor) {}
