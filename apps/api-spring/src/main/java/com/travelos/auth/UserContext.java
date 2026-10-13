package com.travelos.auth;

/** Per-request authenticated user info extracted from the JWT cookie. */
public record UserContext(String id, String username) {
}
