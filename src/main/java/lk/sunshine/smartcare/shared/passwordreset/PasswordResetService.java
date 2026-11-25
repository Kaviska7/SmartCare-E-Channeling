package lk.sunshine.smartcare.shared.passwordreset;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class PasswordResetService {
    private static final Pattern SIX_DIGITS = Pattern.compile("[0-9]{6}");
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_REQUESTS_PER_HOUR = 5;

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate db;
    private final Clock clock;
    private final SecureRandom secureRandom;

    @Autowired
    public PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens,
                                PasswordEncoder passwordEncoder, ApplicationEventPublisher events,
                                JdbcTemplate db, Clock clock) {
        this(users, tokens, passwordEncoder, events, db, clock, new SecureRandom());
    }

    PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens,
                         PasswordEncoder passwordEncoder, ApplicationEventPublisher events,
                         JdbcTemplate db, Clock clock, SecureRandom secureRandom) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.db = db;
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    @Transactional
    public void generateAndSendResetCode(String email) {
        String normalizedEmail = normalize(email);
        Optional<User> lockedUser = users.findActiveByEmailForUpdate(normalizedEmail);
        if (lockedUser.isEmpty()) return;

        User user = lockedUser.get();
        Instant now = clock.instant();
        if (tokens.countByUserAndCreatedAtGreaterThanEqual(user, now.minus(1, ChronoUnit.HOURS))
                >= MAX_REQUESTS_PER_HOUR) return;

        tokens.findByUserAndUsedFalse(user).forEach(token -> token.setUsed(true));
        String rawCode = "%06d".formatted(secureRandom.nextInt(1_000_000));
        tokens.save(new PasswordResetToken(user, passwordEncoder.encode(rawCode),
                now.plus(10, ChronoUnit.MINUTES), now));
        db.update("INSERT INTO audit_event (actor_user_id, action, entity_name, entity_id) "
                + "VALUES (?, 'FORGOT_PASSWORD_REQUEST', 'app_user', ?)", user.getId(), user.getId());
        events.publishEvent(new ResetCodeGeneratedEvent(user.getEmail(), rawCode));
    }

    @Transactional
    public boolean verifyCode(String email, String rawCode) {
        return verifyLocked(email, rawCode).isPresent();
    }

    @Transactional
    public boolean resetPassword(String email, String rawCode, String newPassword) {
        validateNewPassword(newPassword);
        Optional<VerifiedReset> verified = verifyLocked(email, rawCode);
        if (verified.isEmpty()) return false;

        VerifiedReset reset = verified.get();
        reset.token().setUsed(true);
        reset.user().setPasswordHash(passwordEncoder.encode(newPassword));
        db.update("INSERT INTO audit_event (actor_user_id, action, entity_name, entity_id) "
                + "VALUES (?, 'RESET_PASSWORD_SUCCESS', 'app_user', ?)", reset.user().getId(), reset.user().getId());
        return true;
    }

    private Optional<VerifiedReset> verifyLocked(String email, String rawCode) {
        if (!SIX_DIGITS.matcher(rawCode == null ? "" : rawCode).matches()) return Optional.empty();
        Optional<User> lockedUser = users.findActiveByEmailForUpdate(normalize(email));
        if (lockedUser.isEmpty()) return Optional.empty();

        User user = lockedUser.get();
        Optional<PasswordResetToken> active = tokens
                .findFirstByUserAndUsedFalseAndExpiryDateAfterOrderByCreatedAtDesc(user, clock.instant());
        if (active.isEmpty()) return Optional.empty();

        PasswordResetToken token = active.get();
        if (token.getAttemptCount() >= MAX_ATTEMPTS) {
            token.setUsed(true);
            return Optional.empty();
        }
        if (!passwordEncoder.matches(rawCode, token.getHashedCode())) {
            token.incrementAttemptCount();
            if (token.getAttemptCount() >= MAX_ATTEMPTS) token.setUsed(true);
            return Optional.empty();
        }
        return Optional.of(new VerifiedReset(user, token));
    }

    private void validateNewPassword(String password) {
        if (password == null || password.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must not exceed 72 UTF-8 bytes");
        }
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private record VerifiedReset(User user, PasswordResetToken token) {}
}
