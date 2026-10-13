package pt.paulotrevizan.appointments.common;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers("/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .httpBasic(withDefaults())
        .build();
  }
}
