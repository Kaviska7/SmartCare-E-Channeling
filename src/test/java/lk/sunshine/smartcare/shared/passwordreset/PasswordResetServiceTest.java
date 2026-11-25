package lk.sunshine.smartcare.shared.passwordreset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PasswordResetServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-05T04:00:00Z");
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordResetTokenRepository tokens = mock(PasswordResetTokenRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final SecureRandom random = mock(SecureRandom.class);
    private final User user = mock(User.class);
    private final PasswordResetService service = new PasswordResetService(users, tokens, encoder, events, db,
            Clock.fixed(NOW, ZoneOffset.UTC), random);

    @BeforeEach
    void setUp() {
        when(users.findActiveByEmailForUpdate("person@example.com")).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(7L);
        when(user.getEmail()).thenReturn("person@example.com");
    }

    @Test
    void generatesLeadingZeroCodeStoresOnlyHashAndInvalidatesOldTokens() {
        PasswordResetToken old = new PasswordResetToken(user, "old-hash", NOW.plusSeconds(60), NOW.minusSeconds(60));
        when(tokens.findByUserAndUsedFalse(user)).thenReturn(List.of(old));
        when(random.nextInt(1_000_000)).thenReturn(42);
        when(encoder.encode("000042")).thenReturn("bcrypt-hash");

        service.generateAndSendResetCode(" Person@Example.com ");

        assertThat(old.isUsed()).isTrue();
        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokens).save(saved.capture());
        assertThat(saved.getValue().getHashedCode()).isEqualTo("bcrypt-hash");
        assertThat(saved.getValue().getExpiryDate()).isEqualTo(NOW.plusSeconds(600));
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        verify(events).publishEvent(new ResetCodeGeneratedEvent("person@example.com", "000042"));
    }

    @Test
    void sixthRequestWithinRollingHourIsSilentlyRateLimited() {
        when(tokens.countByUserAndCreatedAtGreaterThanEqual(eq(user), any())).thenReturn(5L);

        service.generateAndSendResetCode("person@example.com");

        verify(tokens, never()).save(any());
        verifyNoInteractions(events, encoder, random);
    }

    @Test
    void unknownAccountIsSilentlyIgnored() {
        when(users.findActiveByEmailForUpdate("missing@example.com")).thenReturn(Optional.empty());

        service.generateAndSendResetCode("missing@example.com");

        verifyNoInteractions(tokens, events, encoder, random);
    }

    @Test
    void successfulVerificationDoesNotConsumeToken() {
        PasswordResetToken token = activeToken();
        when(encoder.matches("123456", "hash")).thenReturn(true);

        assertThat(service.verifyCode("person@example.com", "123456")).isTrue();
        assertThat(token.isUsed()).isFalse();
        assertThat(token.getAttemptCount()).isZero();
    }

    @Test
    void fifthFailedAttemptInvalidatesToken() {
        PasswordResetToken token = activeToken();
        for (int i = 0; i < 4; i++) token.incrementAttemptCount();
        when(encoder.matches("654321", "hash")).thenReturn(false);

        assertThat(service.verifyCode("person@example.com", "654321")).isFalse();
        assertThat(token.getAttemptCount()).isEqualTo(5);
        assertThat(token.isUsed()).isTrue();
    }

    @Test
    void resetRechecksCodeConsumesItAndUpdatesPassword() {
        PasswordResetToken token = activeToken();
        when(encoder.matches("123456", "hash")).thenReturn(true);
        when(encoder.encode("new-password")).thenReturn("new-hash");

        assertThat(service.resetPassword("person@example.com", "123456", "new-password")).isTrue();

        assertThat(token.isUsed()).isTrue();
        verify(user).setPasswordHash("new-hash");
        verify(db).update(contains("RESET_PASSWORD_SUCCESS"), eq(7L), eq(7L));
    }

    @Test
    void rejectsNonAsciiOrIncorrectLengthCodesWithoutHashWork() {
        assertThat(service.verifyCode("person@example.com", "１２３４５６")).isFalse();
        assertThat(service.verifyCode("person@example.com", "12345")).isFalse();
        verifyNoInteractions(encoder);
    }

    @Test
    void rejectsPasswordsOverBcryptUtf8Limit() {
        String oversized = "😀".repeat(19);
        assertThatThrownBy(() -> service.resetPassword("person@example.com", "123456", oversized))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("72 UTF-8 bytes");
        verifyNoInteractions(tokens);
    }

    private PasswordResetToken activeToken() {
        PasswordResetToken token = new PasswordResetToken(user, "hash", NOW.plusSeconds(1), NOW.minusSeconds(1));
        when(tokens.findFirstByUserAndUsedFalseAndExpiryDateAfterOrderByCreatedAtDesc(user, NOW))
                .thenReturn(Optional.of(token));
        return token;
    }
}
