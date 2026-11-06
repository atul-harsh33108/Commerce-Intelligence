package com.ecommerce.inventory.exception;

import com.ecommerce.inventory.entity.InsufficientStockException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(404, "Not Found", ex.getMessage(), req.getRequestURI()));
    }

    /**
     * 409, and the body says how many are actually available.
     *
     * <p>Not 400: the request was well formed and would have succeeded a minute ago. Not 500: this
     * is the system working correctly. 409 Conflict is the status for "the state of the resource
     * makes this impossible", and including the available count is what lets a client offer "3 left,
     * order 3?" instead of a dead end.
     */
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> insufficient(InsufficientStockException ex,
                                                      HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(409, "Conflict",
                ex.getMessage(), req.getRequestURI(),
                Map.of("productKey", ex.getProductKey(),
                        "requested", String.valueOf(ex.getRequested()),
                        "available", String.valueOf(ex.getAvailable()))));
    }

    /**
     * A version conflict that outlived every retry.
     *
     * <p>503 with {@code Retry-After}, not 500: nothing is broken, the row is simply too contended
     * right now, and the honest instruction to the caller is "try again shortly". A 500 tells a
     * client to give up and tells an on-call engineer to investigate a bug that does not exist.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> contention(ObjectOptimisticLockingFailureException ex,
                                                    HttpServletRequest req) {
        log.warn("Optimistic lock exhausted on {}: {}", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(ErrorResponse.of(503, "Service Unavailable",
                        "That product is being ordered concurrently. Retry shortly.",
                        req.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> fields = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> fields.put(e.getField(), e.getDefaultMessage()));
        return ResponseEntity.badRequest().body(ErrorResponse.of(400, "Bad Request",
                "Request validation failed.", req.getRequestURI(), fields));
    }
}
