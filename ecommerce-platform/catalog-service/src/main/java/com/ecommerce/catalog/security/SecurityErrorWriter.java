package com.ecommerce.catalog.security;

import com.ecommerce.catalog.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Makes security failures look like every other error this API returns.
 *
 * <p>{@code GlobalExceptionHandler} cannot help here: authentication and authorization are rejected
 * inside the filter chain, <em>before</em> the request reaches a controller, so no
 * {@code @RestControllerAdvice} ever sees them. Left alone, Spring Security returns an empty body —
 * so a client parsing the {@link ErrorResponse} envelope everywhere else gets nothing on the two
 * failures it is most likely to hit.
 *
 * <p>The distinction the two halves encode is worth being precise about:
 * <ul>
 *   <li><strong>401 Unauthorized</strong> — "I do not know who you are." No token, or an invalid or
 *       expired one. Authenticating may fix it.</li>
 *   <li><strong>403 Forbidden</strong> — "I know who you are, and you may not do this." A valid
 *       token without the required role. Re-authenticating changes nothing.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SecurityErrorWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         org.springframework.security.core.AuthenticationException ex) throws IOException {
        write(response, 401, "Unauthorized",
                "Authentication required. Send a valid Bearer token.", request.getRequestURI());
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       org.springframework.security.access.AccessDeniedException ex) throws IOException {
        write(response, 403, "Forbidden",
                "You do not have permission to perform this action.", request.getRequestURI());
    }

    private void write(HttpServletResponse response, int status, String error, String message,
                       String path) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(status, error, message, path));
    }
}
