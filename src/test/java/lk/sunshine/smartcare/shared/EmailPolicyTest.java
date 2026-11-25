package lk.sunshine.smartcare.shared;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailPolicyTest {
    @Test void acceptsAndNormalizesGmailAddresses() {
        assertThat(EmailPolicy.newAddress(" Name+Label@GMAIL.COM ")).isEqualTo("name+label@gmail.com");
    }

    @Test void rejectsNonGmailAndDeceptiveDomains() {
        for (String email : new String[]{"name@example.com", "@gmail.com", "name@mail.gmail.com", "name@gmail.com.example"})
            assertThatThrownBy(() -> EmailPolicy.newAddress(email))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("valid @gmail.com address");
    }

    @Test void unchangedLegacyAddressIsAllowedButChangingItRequiresGmail() {
        assertThat(EmailPolicy.changedAddress(" Legacy@Example.test ", "legacy@example.test"))
                .isEqualTo("legacy@example.test");
        assertThatThrownBy(() -> EmailPolicy.changedAddress("new@example.test", "legacy@example.test"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(EmailPolicy.changedAddress("new@gmail.com", "legacy@example.test"))
                .isEqualTo("new@gmail.com");
    }
}
