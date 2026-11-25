package lk.sunshine.smartcare.shared.passwordreset;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Nationalized;

import java.time.Instant;

@Entity
@Table(name = "password_reset_token")
public class PasswordResetToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "hashed_code", nullable = false, length = 100)
    @Nationalized
    private String hashedCode;

    @Column(name = "expires_at", nullable = false)
    @Convert(converter = UtcInstantConverter.class)
    private Instant expiryDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Convert(converter = UtcInstantConverter.class)
    private Instant createdAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "used", nullable = false)
    private boolean used;

    protected PasswordResetToken() {}

    public PasswordResetToken(User user, String hashedCode, Instant expiryDate, Instant createdAt) {
        this.user = user;
        this.hashedCode = hashedCode;
        this.expiryDate = expiryDate;
        this.createdAt = createdAt;
    }

    @PrePersist
    void initializeDefaults() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getHashedCode() { return hashedCode; }
    public Instant getExpiryDate() { return expiryDate; }
    public Instant getCreatedAt() { return createdAt; }
    public int getAttemptCount() { return attemptCount; }
    public boolean isUsed() { return used; }
    public void incrementAttemptCount() { attemptCount++; }
    public void setUsed(boolean used) { this.used = used; }
}
