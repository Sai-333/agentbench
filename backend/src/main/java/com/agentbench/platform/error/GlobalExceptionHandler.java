package com.agentbench.platform.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        detail.setTitle(ex.getCode());
        detail.setType(URI.create("urn:agentbench:error:" + ex.getCode().toLowerCase()));
        detail.setProperty("code", ex.getCode());
        return detail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatField)
                .collect(Collectors.joining("; "));
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
        detail.setTitle("VALIDATION_FAILED");
        detail.setType(URI.create("urn:agentbench:error:validation_failed"));
        detail.setProperty("code", "VALIDATION_FAILED");
        return detail;
    }

    @ExceptionHandler({BadCredentialsException.class})
    public ProblemDetail handleBadCredentials(RuntimeException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        detail.setTitle("AUTH_UNAUTHORIZED");
        detail.setType(URI.create("urn:agentbench:error:auth_unauthorized"));
        detail.setProperty("code", "AUTH_UNAUTHORIZED");
        return detail;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
        detail.setTitle("AUTH_FORBIDDEN");
        detail.setType(URI.create("urn:agentbench:error:auth_forbidden"));
        detail.setProperty("code", "AUTH_FORBIDDEN");
        return detail;
    }

    private static String formatField(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
