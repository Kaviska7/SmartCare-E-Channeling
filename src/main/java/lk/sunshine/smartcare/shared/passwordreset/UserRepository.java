package lk.sunshine.smartcare.shared.passwordreset;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where lower(u.email) = :email and u.active = true")
    Optional<User> findActiveByEmailForUpdate(@Param("email") String email);
}
