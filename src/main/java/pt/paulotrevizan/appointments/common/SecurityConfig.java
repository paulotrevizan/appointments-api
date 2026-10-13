package pt.paulotrevizan.appointments.common;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfig {

  private static final String BOOKING_CLIENT = "BOOKING_CLIENT";
  private static final String ADMIN = "ADMIN";
  private static final String[] API_DOCS_PATHS = {
    "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
  };

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      SecurityProperties properties,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
      throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests -> {
              requests.requestMatchers("/actuator/health/**").permitAll();
              if (properties.publicApiDocs()) {
                requests.requestMatchers(API_DOCS_PATHS).permitAll();
              }
              requests
                  .requestMatchers(HttpMethod.POST, "/api/v1/appointments")
                  .hasRole(BOOKING_CLIENT)
                  .requestMatchers("/api/v1/admin/**")
                  .hasRole(ADMIN)
                  .anyRequest()
                  .authenticated();
            })
        .httpBasic(
            basic ->
                basic.authenticationEntryPoint(
                    (request, response, ex) ->
                        exceptionResolver.resolveException(request, response, null, ex)))
        .exceptionHandling(
            handling ->
                handling.accessDeniedHandler(
                    (request, response, ex) ->
                        exceptionResolver.resolveException(request, response, null, ex)))
        .build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  UserDetailsService userDetailsService(SecurityProperties properties, PasswordEncoder encoder) {
    return new InMemoryUserDetailsManager(
        User.withUsername("booking-client")
            .password(encoder.encode(properties.bookingClientPassword()))
            .roles(BOOKING_CLIENT)
            .build(),
        User.withUsername("admin")
            .password(encoder.encode(properties.adminPassword()))
            .roles(ADMIN)
            .build());
  }
}
