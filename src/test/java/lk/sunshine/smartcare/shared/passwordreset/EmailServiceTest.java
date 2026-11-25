package lk.sunshine.smartcare.shared.passwordreset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class EmailServiceTest {
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final Environment environment = mock(Environment.class);

    @Test
    void productionFailureDoesNotLogRawCode(CapturedOutput output) {
        when(sender.createMimeMessage()).thenThrow(new IllegalStateException("mail unavailable"));
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);

        new EmailService(sender, environment, "no-reply@example.com", true)
                .sendResetCode("person@example.com", "123456");

        assertThat(output).contains("Could not deliver password reset email").doesNotContain("123456");
    }

    @Test
    void developmentFailureDoesNotLogRawCodeWithoutExplicitOptIn(CapturedOutput output) {
        when(sender.createMimeMessage()).thenThrow(new IllegalStateException("mail unavailable"));
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);

        new EmailService(sender, environment, "no-reply@example.com", false)
                .sendResetCode("person@example.com", "000042");

        assertThat(output).contains("Could not deliver password reset email").doesNotContain("000042");
    }

    @Test
    void optedInDevelopmentFailureLogsRawCode(CapturedOutput output) {
        when(sender.createMimeMessage()).thenThrow(new IllegalStateException("mail unavailable"));
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);

        new EmailService(sender, environment, "no-reply@example.com", true)
                .sendResetCode("person@example.com", "000042");

        assertThat(output).contains("DEVELOPMENT ONLY").contains("000042");
    }
}
