package com.ecommerce.order.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(OrderNotAllowedException.class)
    public ResponseEntity<ErrorResponse> forbidden(OrderNotAllowedException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(403, "Forbidden", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(OutOfStockException.class)
    public ResponseEntity<ErrorResponse> outOfStock(OutOfStockException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(409, "Conflict",
                "One or more items are no longer available in the quantity requested.",
                req.getRequestURI(), Map.of("detail", ex.getMessage())));
    }

    /**
     * 503 with {@code Retry-After}, for both "dependency down" cases.
     *
     * <p>The header is the part that is easy to leave out and is the only machine-readable half: a
     * client that reads it backs off, and one that does not at least sees a status that means "later,
     * not never". A bare 500 says "we have a bug", which is a lie that sends somebody looking.
     */
    @ExceptionHandler({CatalogUnavailableException.class, InventoryUnavailableException.class})
    public ResponseEntity<ErrorResponse> unavailable(RuntimeException ex, HttpServletRequest req) {
        log.warn("Dependency unavailable on {}: {}", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(ErrorResponse.of(503, "Service Unavailable", ex.getMessage(),
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
