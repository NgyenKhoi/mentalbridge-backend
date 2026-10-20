package com.mentalbridge.community.chat;

import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {CommunityChatController.class, CommunityGroupController.class, CommunityChatReportController.class})
class ChatProblems {
    @ExceptionHandler(ChatException.class)
    ResponseEntity<Map<String, Object>> domain(ChatException exception, HttpServletRequest request) {
        var response = ResponseEntity.status(exception.status).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON);
        if (exception.status == 429) response.header("Retry-After", "60");
        return response.body(body(exception.status, exception.code, CommunityChatController.correlation(request.getHeader("X-Correlation-Id"))));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            org.springframework.web.HttpMediaTypeNotSupportedException.class})
    ResponseEntity<Map<String, Object>> malformed(Exception exception, HttpServletRequest request) {
        return domain(ChatException.invalid(), request);
    }
    @ExceptionHandler({org.springframework.dao.DataAccessException.class, org.springframework.transaction.TransactionSystemException.class})
    ResponseEntity<Map<String, Object>> persistence(Exception exception, HttpServletRequest request) {
        return domain(ChatException.unavailable(), request);
    }
    static Map<String, Object> body(int status, String code, UUID correlation) {
        return Map.of("type", "about:blank", "title", "Community chat request could not be completed",
                "status", status, "code", code, "correlationId", correlation);
    }
}
