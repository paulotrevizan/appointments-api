package pt.paulotrevizan.appointments.common.web;

import java.util.stream.Collectors;
import org.springframework.beans.TypeMismatchException;
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
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ProblemDetailHandler extends ResponseEntityExceptionHandler {

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
    return respond(ex.getStatus(), ex.getCode(), ex.getMessage());
  }

  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
    ProblemDetail problem = problem(HttpStatus.UNAUTHORIZED, null, "Authentication is required");
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"appointments-api\"")
        .body(problem);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
    return respond(HttpStatus.FORBIDDEN, null, "Access is denied");
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

  // Constraints on query parameters, e.g. limit outside 1–100.
  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    String detail =
        ex.getParameterValidationResults().stream()
            .flatMap(
                result ->
                    result.getResolvableErrors().stream()
                        .map(
                            error ->
                                result.getMethodParameter().getParameterName()
                                    + " "
                                    + error.getDefaultMessage()))
            .sorted()
            .collect(Collectors.joining("; "));
    return validationError(detail);
  }

  // A query parameter that cannot be converted, e.g. limit=abc.
  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    return validationError(ex.getPropertyName() + " has an invalid value");
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
        .body(problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail));
  }

  private ResponseEntity<ProblemDetail> respond(HttpStatus status, String code, String detail) {
    return ResponseEntity.status(status).body(problem(status, code, detail));
  }

  // RFC 9457: no custom `type`, so it stays "about:blank" and the title is the HTTP status phrase.
  private ProblemDetail problem(HttpStatus status, String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    if (code != null) {
      problem.setProperty("code", code);
    }
    return problem;
  }
}
