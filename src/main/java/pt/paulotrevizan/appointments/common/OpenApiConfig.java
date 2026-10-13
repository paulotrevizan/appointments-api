package pt.paulotrevizan.appointments.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** D-026: the API document and Swagger UI sit behind the same HTTP Basic authentication. */
@Configuration
class OpenApiConfig {

  private static final String BASIC = "basicAuth";

  @Bean
  OpenAPI openApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Appointments API")
                .version("v1")
                .description(
                    "Book medical appointments. Authenticate with HTTP Basic: `booking-client`"
                        + " (role BOOKING_CLIENT) books, `admin` (role ADMIN) lists."))
        .components(
            new Components()
                .addSecuritySchemes(
                    BASIC, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
        .addSecurityItem(new SecurityRequirement().addList(BASIC));
  }
}
