package pt.paulotrevizan.appointments.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

  private static final String BASIC = "basicAuth";

  @Bean
  OpenAPI openApi() {
    return new OpenAPI()
        .info(new Info().title("Appointments API").version("v1"))
        .components(
            new Components()
                .addSecuritySchemes(
                    BASIC, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
        .addSecurityItem(new SecurityRequirement().addList(BASIC));
  }

  @Bean
  OpenApiCustomizer bookingRequestExample() {
    return openApi ->
        openApi
            .getComponents()
            .getSchemas()
            .get("BookAppointmentRequest")
            .setExample(
                Map.of(
                    "patientId", "00000000-0000-0000-0000-000000000001",
                    "specialty", "CARDIOLOGY",
                    "startAt", "2030-01-07T14:00:00Z"));
  }
}
