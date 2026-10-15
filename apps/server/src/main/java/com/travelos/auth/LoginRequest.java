package com.travelos.auth;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public record LoginRequest(
    @NotBlank @Size(max = 100) String username,
    @NotBlank @Size(max = 200) String password) {
}
