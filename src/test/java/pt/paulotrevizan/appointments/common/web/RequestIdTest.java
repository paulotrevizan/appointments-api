package pt.paulotrevizan.appointments.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import pt.paulotrevizan.appointments.TestcontainersConfiguration;
import pt.paulotrevizan.appointments.booking.BookingFixtures;
import pt.paulotrevizan.appointments.catalog.domain.Patient;
import pt.paulotrevizan.appointments.catalog.domain.Specialty;
import pt.paulotrevizan.appointments.catalog.persistence.DoctorRepository;
import pt.paulotrevizan.appointments.catalog.persistence.PatientRepository;
import pt.paulotrevizan.appointments.catalog.persistence.RoomRepository;
import pt.paulotrevizan.appointments.catalog.persistence.SpecialtyRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class RequestIdTest {

  private static final String HEADER = "X-Request-Id";

  private final MockMvcTester mvc;
  private final BookingFixtures fixtures;

  RequestIdTest(
      @Autowired MockMvcTester mvc,
      @Autowired Clock clock,
      @Autowired PatientRepository patients,
      @Autowired SpecialtyRepository specialties,
      @Autowired DoctorRepository doctors,
      @Autowired RoomRepository rooms) {
    this.mvc = mvc;
    this.fixtures = new BookingFixtures(patients, specialties, doctors, rooms, clock);
  }

  @Test
  @DisplayName("A request without X-Request-Id gets a generated one in the response")
  void givenNoRequestId_whenCalling_thenResponseCarriesGeneratedId() {
    MvcTestResult result = mvc.get().uri("/actuator/health").exchange();

    assertThat(result).hasStatusOk();
    assertThat(UUID.fromString(result.getResponse().getHeader(HEADER))).isNotNull();
  }

  @Test
  @DisplayName("The caller's X-Request-Id is echoed, also on a 401")
  void givenRequestId_whenUnauthorized_thenSameIdEchoed() {
    MvcTestResult result =
        mvc.get().uri("/api/v1/admin/appointments").header(HEADER, "gateway-7f3a.42").exchange();

    assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
    assertThat(result).headers().hasValue(HEADER, "gateway-7f3a.42");
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(
      strings = {
        "contains spaces",
        "line\\nbreak",
        "{\"json\":true}",
        "0123456789012345678901234567890123456789012345678901234567890123456789"
      })
  @DisplayName("An unsafe X-Request-Id is replaced, so callers cannot forge log lines")
  void givenUnsafeRequestId_whenCalling_thenReplacedByGeneratedId(String unsafe) {
    MvcTestResult result = mvc.get().uri("/actuator/health").header(HEADER, unsafe).exchange();

    String echoed = result.getResponse().getHeader(HEADER);
    assertThat(echoed).isNotEqualTo(unsafe);
    assertThat(UUID.fromString(echoed)).isNotNull();
  }

  @Test
  @DisplayName("Log lines written while handling a request carry its request id")
  void givenRequestId_whenBooking_thenLogLinesCarryIt(CapturedOutput output) {
    Specialty specialty = fixtures.specialty();
    fixtures.doctors(specialty, 1);
    fixtures.rooms(1);
    Patient patient = fixtures.patient();

    MvcTestResult result =
        mvc.post()
            .uri("/api/v1/appointments")
            .headers(headers -> headers.setBasicAuth("booking-client", "booking-test"))
            .header(HEADER, "booking-request-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"patientId": "%s", "specialty": "%s", "startAt": "2035-03-05T14:00:00Z"}
                """
                    .formatted(patient.getId(), specialty.getCode()))
            .exchange();

    assertThat(result).hasStatus(HttpStatus.CREATED);
    assertThat(output.getOut().lines())
        .anySatisfy(line -> assertThat(line).contains("[booking-request-1]").contains(" booked"));
  }
}
