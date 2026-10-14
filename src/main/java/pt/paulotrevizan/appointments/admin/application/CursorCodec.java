package pt.paulotrevizan.appointments.admin.application;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pt.paulotrevizan.appointments.admin.domain.InvalidCursorException;
import pt.paulotrevizan.appointments.booking.domain.Appointment;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * D-016: the cursor is opaque to clients (base64url of {@code {startAt, id}}), so its format can
 * change without breaking them. Not signed: tampering only reaches rows an admin may already see.
 */
@Component
@RequiredArgsConstructor
class CursorCodec {

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final JsonMapper jsonMapper;

  String encode(Cursor cursor) {
    return ENCODER.encodeToString(jsonMapper.writeValueAsBytes(cursor));
  }

  Cursor decode(String value) {
    Cursor cursor;
    try {
      cursor = jsonMapper.readValue(DECODER.decode(value), Cursor.class);
    } catch (IllegalArgumentException | JacksonException e) {
      throw new InvalidCursorException();
    }
    if (cursor == null || cursor.startAt() == null || cursor.id() == null) {
      throw new InvalidCursorException();
    }
    return cursor;
  }

  /** The position after which the next page starts: the last item of the current page. */
  record Cursor(Instant startAt, UUID id) {

    static Cursor after(Appointment appointment) {
      return new Cursor(appointment.getStartAt(), appointment.getId());
    }
  }
}
