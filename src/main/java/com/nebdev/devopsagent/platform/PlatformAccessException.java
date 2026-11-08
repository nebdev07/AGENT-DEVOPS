package com.nebdev.devopsagent.platform;

/** Accès refusé (session, permission ou grant serveur). */
public class PlatformAccessException extends RuntimeException {

    private final int status;

    public PlatformAccessException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static PlatformAccessException unauthorized(String message) {
        return new PlatformAccessException(401, message);
    }

    public static PlatformAccessException forbidden(String message) {
        return new PlatformAccessException(403, message);
    }
}
