package lk.sunshine.smartcare.shared;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/account")
public class AccountController {
    private final JdbcTemplate db;
    private final CurrentUser users;
    private final PasswordEncoder encoder;

    public AccountController(JdbcTemplate db, CurrentUser users, PasswordEncoder encoder) {
        this.db = db;
        this.users = users;
        this.encoder = encoder;
    }

    public record Details(@NotBlank String fullName, @Email @NotBlank String email) {}
    public record PasswordChange(@NotBlank String currentPassword, @NotBlank @Size(min=8) String newPassword) {}

    @GetMapping
    public Map<String,Object> details(Authentication auth) {
        return db.queryForMap("SELECT email,full_name,role FROM app_user WHERE id=?", users.id(auth));
    }

    @PutMapping
    @Transactional
    public Map<String,Object> update(Authentication auth, @Valid @RequestBody Details input) {
        long id = users.id(auth);
        String email = EmailPolicy.changedAddress(input.email(), auth.getName());
        if (db.queryForObject("SELECT COUNT(*) FROM app_user WHERE email=? AND id<>?", Integer.class, email, id) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already in use");
        boolean changed = !auth.getName().equalsIgnoreCase(email);
        db.update("UPDATE app_user SET email=?,full_name=? WHERE id=?", email, input.fullName().trim(), id);
        if("DOCTOR".equals(users.role(auth)))db.update("UPDATE doctor SET display_name=? WHERE user_id=?",input.fullName().trim(),id);
        db.update("INSERT INTO audit_event(actor_user_id,action,entity_name,entity_id) VALUES(?,'UPDATE_ACCOUNT','app_user',?)", id, id);
        return Map.of("emailChanged", changed);
    }

    @PutMapping("/password")
    @Transactional
    public Map<String,String> password(Authentication auth, @Valid @RequestBody PasswordChange input) {
        long id = users.id(auth);
        String currentHash = db.queryForObject("SELECT password_hash FROM app_user WHERE id=?", String.class, id);
        if (!encoder.matches(input.currentPassword(), currentHash))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        db.update("UPDATE app_user SET password_hash=? WHERE id=?", encoder.encode(input.newPassword()), id);
        users.audit(auth, "CHANGE_PASSWORD", "app_user", id);
        return Map.of("message", "Password changed");
    }
}
