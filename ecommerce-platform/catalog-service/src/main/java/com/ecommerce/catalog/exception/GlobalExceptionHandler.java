package com.ecommerce.catalog.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns exceptions into the one error envelope, in one place.
 *
 * <p>What it deliberately does <em>not</em> catch: authentication and authorization failures. Those
 * are rejected inside the Spring Security filter chain, before any controller is reached, so no
 * {@code @RestControllerAdvice} ever sees them. {@code SecurityErrorWriter} produces the same
 * envelope for 401 and 403 — see the comment there.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(ResourceNotFoundException ex,
                                                  HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ErrorResponse.of(404, "Not Found", ex.getMessage(), request.getRequestURI()));
    }

    /**
     * 409, not 500, and not 400.
     *
     * <p>Two clients read the same product, both edited it, the second write lost. That is not the
     * second client's fault (400 blames the request) and not a server fault (500 says "try again and
     * hope"). 409 Conflict says precisely what happened: the resource moved under you, re-read it.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> conflict(OptimisticLockingFailureException ex,
                                                  HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(409, "Conflict",
                "This product was modified by someone else. Re-read it and retry.",
                request.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> integrity(DataIntegrityViolationException ex,
                                                   HttpServletRequest request) {
        // The exception's message contains the constraint name and often the offending value; that
        // goes in the log, not in the response, because a constraint name discloses the schema.
        log.warn("Constraint violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(409, "Conflict",
                "That product key already exists.", request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException ex,
                                                 HttpServletRequest request) {
        Map<String, String> fields = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> fields.put(e.getField(), e.getDefaultMessage()));
        return ResponseEntity.badRequest().body(ErrorResponse.of(400, "Bad Request",
                "Request validation failed.", request.getRequestURI(), fields));
    }
}
