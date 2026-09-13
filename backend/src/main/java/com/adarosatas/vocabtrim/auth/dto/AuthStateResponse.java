package com.adarosatas.vocabtrim.auth.dto;

public record AuthStateResponse(
        boolean authenticated,
        UserView user,
        String csrfToken
) {
}
