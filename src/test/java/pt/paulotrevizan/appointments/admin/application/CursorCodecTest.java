package pt.paulotrevizan.appointments.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pt.paulotrevizan.appointments.admin.application.CursorCodec.Cursor;
import pt.paulotrevizan.appointments.admin.domain.InvalidCursorException;
import tools.jackson.databind.json.JsonMapper;

class CursorCodecTest {

  private final CursorCodec codec = new CursorCodec(JsonMapper.builder().build());

  @Test
  @DisplayName("D-016: a cursor decodes back to the same position")
  void givenCursor_whenEncodedAndDecoded_thenSamePosition() {
    Cursor cursor = new Cursor(Instant.parse("2035-03-05T14:00:00Z"), UUID.randomUUID());

    assertThat(codec.decode(codec.encode(cursor))).isEqualTo(cursor);
  }

  @Test
  @DisplayName("D-016: a cursor is URL-safe base64 of {startAt, id}, without padding")
  void givenCursor_whenEncoded_thenUrlSafeBase64OfStartAtAndId() {
    UUID id = UUID.fromString("6f1c2a9e-0000-4000-8000-000000000001");

    String encoded = codec.encode(new Cursor(Instant.parse("2026-10-20T14:00:00Z"), id));

    assertThat(encoded).matches("[A-Za-z0-9_-]+");
    assertThat(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8))
        .isEqualTo("{\"startAt\":\"2026-10-20T14:00:00Z\",\"id\":\"" + id + "\"}");
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(
      strings = {
        "not base64!",
        "bm90IGpzb24", // "not json"
        "bnVsbA", // "null"
        "e30", // "{}"
        "eyJzdGFydEF0IjoiMjAzNS0wMy0wNVQxNDowMDowMFoifQ", // {"startAt":"2035-03-05T14:00:00Z"}
        "eyJzdGFydEF0IjoieCIsImlkIjoieSJ9" // {"startAt":"x","id":"y"}
      })
  @DisplayName("A malformed cursor is rejected with INVALID_CURSOR")
  void givenMalformedCursor_whenDecoding_thenInvalidCursor(String cursor) {
    assertThatThrownBy(() -> codec.decode(cursor))
        .isInstanceOf(InvalidCursorException.class)
        .extracting("code")
        .isEqualTo("INVALID_CURSOR");
  }
}
