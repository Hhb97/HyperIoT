package io.hyperiot.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record PasswordRequest(String oldPassword, String newPassword) {
    }

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public AuthService.Token login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return auth.login(req.username(), req.password(), http.getRemoteAddr());
    }

    @GetMapping("/me")
    public Map<String, String> me(Authentication authentication) {
        return Map.of("username", authentication.getName(), "role", "ADMIN");
    }

    @PostMapping("/password")
    public ResponseEntity<Void> password(@RequestBody PasswordRequest req, Authentication authentication) {
        auth.changePassword(authentication.getName(), req.oldPassword(), req.newPassword());
        return ResponseEntity.noContent().build();
    }
}
