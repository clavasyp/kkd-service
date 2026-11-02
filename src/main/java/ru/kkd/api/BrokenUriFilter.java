package ru.kkd.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/**
 * Испорченная запись %XX в параметре пути, например /api/runs;x=%zz: 400 в едином формате, раздел 8.5 design.md.
 * Tomcat часть сегмента после ; не проверяет, а Spring MVC декодирует её до своего обработчика ошибок
 * и без этого фильтра отвечал бы 500 с HTML.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BrokenUriFilter extends OncePerRequestFilter {

    static final String MESSAGE = "Адрес запроса содержит испорченное кодирование";

    private final ObjectMapper objectMapper;

    public BrokenUriFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (decodes(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(), ApiErrorResponse.of(ApiExceptionHandler.REQUEST_NOT_SUPPORTED, MESSAGE));
    }

    // Адрес целиком, а не только параметры пути: пустое имя, как в ;=%zz, Spring пропускает при разборе пути,
    // но декодирует позже, при записи ответа. Части адреса вне параметров Tomcat уже проверил сам.
    private static boolean decodes(String requestUri) {
        try {
            UriUtils.decode(requestUri, StandardCharsets.UTF_8);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
