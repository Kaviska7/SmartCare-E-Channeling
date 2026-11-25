package lk.sunshine.smartcare.shared.passwordreset;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;

@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private final JavaMailSender mailSender;
    private final Environment environment;
    private final String from;
    private final boolean logCodeOnFailure;

    public EmailService(JavaMailSender mailSender, Environment environment,
                        @Value("${smartcare.password-reset.from}") String from,
                        @Value("${smartcare.password-reset.log-code-on-delivery-failure:false}") boolean logCodeOnFailure) {
        this.mailSender = mailSender;
        this.environment = environment;
        this.from = from;
        this.logCodeOnFailure = logCodeOnFailure;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResetCodeGenerated(ResetCodeGeneratedEvent event) {
        sendResetCode(event.email(), event.rawCode());
    }

    public void sendResetCode(String email, String rawCode) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("Your SmartCare password reset code");
            String plain = "Your SmartCare password reset code is " + rawCode
                    + ". It expires in 10 minutes. If you did not request it, ignore this email.";
            String html = "<div style=\"font-family:Arial,sans-serif;max-width:560px\">"
                    + "<h2>Reset your SmartCare password</h2>"
                    + "<p>Use this one-time code to reset your password:</p>"
                    + "<p style=\"font-size:32px;font-weight:700;letter-spacing:8px\">" + rawCode + "</p>"
                    + "<p>This code expires in 10 minutes. If you did not request it, ignore this email.</p>"
                    + "</div>";
            helper.setText(plain, html);
            mailSender.send(message);
        } catch (Exception exception) {
            log.warn("Could not deliver password reset email to {}: {}", email, exception.getClass().getSimpleName());
            if (logCodeOnFailure && environment.acceptsProfiles(Profiles.of("dev", "local"))) {
                log.warn("DEVELOPMENT ONLY - password reset code for {}: {}", email, rawCode);
            }
        }
    }
}
