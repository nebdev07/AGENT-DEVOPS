package com.nebdev.devopsagent.saas.shared;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Erreur métier de l'API v2. {@code code} est stable (contrat client) et sert de clé i18n
 * ({@code error.<code>} dans {@code i18n/messages*.properties}).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Object[] args;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, Map<String, Object> details, Object... args) {
        super(code);
        this.status = status;
        this.code = code;
        this.args = args;
        this.details = details == null ? Map.of() : details;
    }

    public ApiException(HttpStatus status, String code, Object... args) {
        this(status, code, Map.of(), args);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Object[] args() { return args; }
    public Map<String, Object> details() { return details; }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", what);
    }

    public static ApiException forbidden(String code) {
        return new ApiException(HttpStatus.FORBIDDEN, code);
    }

    public static ApiException badRequest(String code, Object... args) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, args);
    }

    public static ApiException conflict(String code, Object... args) {
        return new ApiException(HttpStatus.CONFLICT, code, args);
    }

    public static ApiException unprocessable(String code, Object... args) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, args);
    }

    public static ApiException unauthorized(String code) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code);
    }
}
