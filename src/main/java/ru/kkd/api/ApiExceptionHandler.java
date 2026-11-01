package ru.kkd.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import ru.kkd.service.DbState;
import ru.kkd.service.KkdException;

/** Все ошибки в едином формате, статус HTTP выбирается здесь: раздел 8.5 design.md. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String REQUEST_NOT_SUPPORTED = "REQUEST_NOT_SUPPORTED";
    public static final String DB_NOT_READY = "DB_NOT_READY";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(KkdException.class)
    public ResponseEntity<Object> handleKkdException(KkdException e) {
        HttpStatus status = statusOf(e.code());
        // Кода нет в таблице раздела 8.5: для клиента это непредвиденная ошибка.
        if (status.is5xxServerError()) {
            return internalError(e, new HttpHeaders());
        }
        return response(status, new HttpHeaders(), e.code(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception e) {
        if (DbState.isNotReady(e)) {
            // Обычное состояние между откатом и накатом. Смену состояния один раз пишет RunPoller.
            log.debug("База не готова, запрос отклонён: {}", DbState.shortReason(e));
            return response(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    new HttpHeaders(),
                    DB_NOT_READY,
                    "База данных не готова, повторите запрос позже");
        }
        return internalError(e, new HttpHeaders());
    }

    /** Ошибки самого Spring MVC: неизвестный путь, неверный метод, 406 и другие. Заголовки вроде Allow сохраняются. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> error = springMvcError(e, headers, status);
        // Родитель проверяет, что ответ ещё не отправлен.
        return super.handleExceptionInternal(e, error.getBody(), error.getHeaders(), error.getStatusCode(), request);
    }

    private ResponseEntity<Object> springMvcError(Exception e, HttpHeaders headers, HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return internalError(e, headers);
        }
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return response(status, headers, NOT_FOUND, "Путь не найден");
        }
        if (status.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
            return response(status, headers, METHOD_NOT_ALLOWED, "Метод не поддерживается");
        }
        return response(status, headers, REQUEST_NOT_SUPPORTED, "Запрос не поддерживается");
    }

    private ResponseEntity<Object> internalError(Exception e, HttpHeaders headers) {
        log.error("Непредвиденная ошибка при обработке запроса", e);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR, headers, INTERNAL_ERROR, "Внутренняя ошибка сервера");
    }

    private static HttpStatus statusOf(String code) {
        return switch (code) {
            case KkdException.INVALID_PARAM -> HttpStatus.UNPROCESSABLE_ENTITY;
            case KkdException.RUN_NOT_FOUND, KkdException.UNIT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case KkdException.RUN_FAILED, KkdException.RUN_IN_PROGRESS -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    // Тип ответа задан явно: иначе при Accept: application/xml тело ошибки снова не удалось бы записать.
    private static ResponseEntity<Object> response(
            HttpStatusCode status, HttpHeaders headers, String code, String message) {
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.putAll(headers);
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(ApiErrorResponse.of(code, message), jsonHeaders, status);
    }
}
