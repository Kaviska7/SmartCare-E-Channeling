package lk.sunshine.smartcare.shared;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import lk.sunshine.smartcare.shared.passwordreset.PasswordResetService;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate db;
    private final CurrentUser users;
    private final PasswordResetService passwordResetService;

    public AuthController(JdbcTemplate db, CurrentUser users, PasswordResetService passwordResetService) {
        this.db = db;
        this.users = users;
        this.passwordResetService = passwordResetService;
    }

    public record ForgotPasswordRequest(@NotBlank @Email String email) {}
    public record VerifyResetCodeRequest(@NotBlank @Email String email, @NotBlank String rawCode) {}
    public record ResetPasswordRequest(@NotBlank @Email String email, @NotBlank String rawCode,
                                       @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String newPassword) {}

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    @GetMapping("/session")
    public Map<String, Object> session(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return Map.of("authenticated", false);
        }
        return db.queryForMap("SELECT id,email,role,full_name FROM app_user WHERE id=?", users.id(auth));
    }

    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(@Valid @RequestBody ForgotPasswordRequest input) {
        passwordResetService.generateAndSendResetCode(input.email());
        return Map.of("ok", true, "message",
                "If an account with this email exists, a password reset code has been sent.");
    }

    @PostMapping("/verify-reset-code")
    public Map<String, Boolean> verifyResetCode(@Valid @RequestBody VerifyResetCodeRequest input) {
        return Map.of("valid", passwordResetService.verifyCode(input.email(), input.rawCode()));
    }

    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@Valid @RequestBody ResetPasswordRequest input) {
        if (!passwordResetService.resetPassword(input.email(), input.rawCode(), input.newPassword()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired password reset code");
        return Map.of("ok", true, "message", "Password has been successfully reset. Please sign in with your new password.");
    }
}
