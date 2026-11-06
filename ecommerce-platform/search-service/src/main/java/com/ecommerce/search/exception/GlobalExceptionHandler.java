package com.ecommerce.search.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(404, "Not Found", ex.getMessage(), req.getRequestURI()));
    }

    /**
     * Elasticsearch is unreachable.
     *
     * <p>503 with Retry-After, never 500. Search being down is not the shop being down - the
     * catalogue still serves browse and the checkout still works - and the status code is how a
     * frontend knows to hide the search box rather than show an error page.
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ErrorResponse> unavailable(DataAccessResourceFailureException ex,
                                                     HttpServletRequest req) {
        log.error("Elasticsearch unavailable on {}: {}", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(ErrorResponse.of(503, "Service Unavailable",
                        "Search is temporarily unavailable. Browsing is unaffected.",
                        req.getRequestURI()));
    }
}
