package lk.sunshine.smartcare.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    @Value("${smartcare.bootstrap-admin-email:}") private String email;
    @Value("${smartcare.bootstrap-admin-password:}") private String password;

    public AdminBootstrap(JdbcTemplate db, PasswordEncoder encoder) {
        this.db = db;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() && password.isBlank()) return;
        if (email.isBlank() || password.length() < 8)
            throw new IllegalStateException("Set a bootstrap administrator email and a password of at least 8 characters");
        if (db.queryForObject("SELECT COUNT(*) FROM app_user WHERE role='ADMIN'", Integer.class) > 0) return;
        db.update("INSERT INTO app_user(email,password_hash,role,full_name) VALUES(?,?,'ADMIN','Administrator')",
                email.trim().toLowerCase(), encoder.encode(password));
    }
}
