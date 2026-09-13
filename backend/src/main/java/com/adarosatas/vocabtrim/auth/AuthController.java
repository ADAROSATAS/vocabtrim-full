package com.adarosatas.vocabtrim.auth;

import com.adarosatas.vocabtrim.auth.dto.AuthStateResponse;
import com.adarosatas.vocabtrim.auth.dto.RegisterRequest;
import com.adarosatas.vocabtrim.auth.dto.UserView;
import com.adarosatas.vocabtrim.security.VocabTrimPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @GetMapping("/me")
    public AuthStateResponse me(Authentication authentication, CsrfToken csrfToken) {
        String token = csrfToken.getToken();
        if (
            authentication != null
            && authentication.getPrincipal() instanceof VocabTrimPrincipal principal
        ) {
            return new AuthStateResponse(
                    true,
                    new UserView(principal.getId(), principal.getUsername()),
                    token
            );
        }
        return new AuthStateResponse(false, null, token);
    }
}
