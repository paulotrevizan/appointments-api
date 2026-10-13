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

/** D-012: service-to-service HTTP Basic with one role per kind of caller. */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfig {

  private static final String BOOKING_CLIENT = "BOOKING_CLIENT";
  private static final String ADMIN = "ADMIN";

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
      throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers("/actuator/health/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/appointments")
                    .hasRole(BOOKING_CLIENT)
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole(ADMIN)
                    .anyRequest()
                    .authenticated())
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
