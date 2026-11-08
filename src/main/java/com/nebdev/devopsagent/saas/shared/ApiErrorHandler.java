package com.nebdev.devopsagent.saas.shared;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Gestion centralisée des erreurs de l'API v2 : code stable + message traduit + identifiant de corrélation.
 * Aucune trace technique (pile, SQL) n'est renvoyée au client.
 */
@RestControllerAdvice(basePackages = "com.nebdev.devopsagent.saas")
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    private final MessageSource messages;

    public ApiErrorHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException ex) {
        return body(ex.status(), ex.code(), ex.args(), ex.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, "validation_failed", new Object[0], Map.of("fields", fields));
    }

    @ExceptionHandler({ConstraintViolationException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> malformed(Exception ex) {
        log.debug("Requête invalide : {}", ex.getMessage());
        return body(HttpStatus.BAD_REQUEST, "bad_request", new Object[0], Map.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> stale(ObjectOptimisticLockingFailureException ex) {
        return body(HttpStatus.CONFLICT, "concurrent_modification", new Object[0], Map.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException ex) {
        log.info("Contrainte d'intégrité : {}", ex.getMostSpecificCause().getMessage());
        return body(HttpStatus.CONFLICT, "conflict", new Object[0], Map.of());
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> denied(Exception ex) {
        return body(HttpStatus.FORBIDDEN, "forbidden", new Object[0], Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        log.error("Erreur inattendue (cid={})", MDC.get(CorrelationIdFilter.MDC_KEY), ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", new Object[0], Map.of());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, Object[] args,
                                                     Map<String, Object> details) {
        Locale locale = LocaleContextHolder.getLocale();
        String message = messages.getMessage("error." + code, args, code, locale);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("message", message);
        out.put("status", status.value());
        String cid = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (cid != null) {
            out.put("correlationId", cid);
        }
        if (details != null && !details.isEmpty()) {
            out.put("details", details);
        }
        return ResponseEntity.status(status).body(out);
    }
}
