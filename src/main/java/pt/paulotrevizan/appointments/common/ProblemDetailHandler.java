package pt.paulotrevizan.appointments.common;

import java.net.URI;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** The single place where errors become RFC 9457 problem details with a stable {@code code}. */
@RestControllerAdvice
class ProblemDetailHandler extends ResponseEntityExceptionHandler {

  private static final String TYPE_BASE = "https://api.example.com/problems/";

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
    return respond(ex.getStatus(), ex.getCode(), ex.getTitle(), ex.getMessage());
  }

  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
    ProblemDetail problem =
        problem(HttpStatus.UNAUTHORIZED, null, "Unauthorized", "Authentication is required");
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"appointments-api\"")
        .body(problem);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
    return respond(HttpStatus.FORBIDDEN, null, "Forbidden", "Access is denied");
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    String detail =
        ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .sorted()
            .collect(Collectors.joining("; "));
    return validationError(detail);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return validationError("Request body is missing or malformed");
  }

  private ResponseEntity<Object> validationError(String detail) {
    return ResponseEntity.badRequest()
        .body(problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation error", detail));
  }

  private ResponseEntity<ProblemDetail> respond(
      HttpStatus status, String code, String title, String detail) {
    return ResponseEntity.status(status).body(problem(status, code, title, detail));
  }

  private ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    if (code != null) {
      problem.setType(URI.create(TYPE_BASE + code.toLowerCase(Locale.ROOT).replace('_', '-')));
      problem.setProperty("code", code);
    }
    return problem;
  }
}
