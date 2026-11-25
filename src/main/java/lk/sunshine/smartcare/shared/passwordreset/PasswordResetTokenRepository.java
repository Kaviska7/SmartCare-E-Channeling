package lk.sunshine.smartcare.shared.passwordreset;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    long countByUserAndCreatedAtGreaterThanEqual(User user, Instant since);

    List<PasswordResetToken> findByUserAndUsedFalse(User user);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordResetToken> findFirstByUserAndUsedFalseAndExpiryDateAfterOrderByCreatedAtDesc(
            User user, Instant now);
}
