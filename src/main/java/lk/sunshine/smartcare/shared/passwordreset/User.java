package lk.sunshine.smartcare.shared.passwordreset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Nationalized;

@Entity(name = "AppUser")
@Table(name = "app_user")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    @Nationalized
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    @Nationalized
    private String passwordHash;

    @Column(nullable = false)
    private boolean active;

    protected User() {}

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isActive() { return active; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
