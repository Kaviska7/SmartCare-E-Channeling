package lk.sunshine.smartcare.shared.passwordreset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Explicit local credential check. Authenticates without sending an email. */
@EnabledIfEnvironmentVariable(named = "SMARTCARE_GMAIL_SMOKE", matches = "true")
class GmailConnectionTest {
    @Test
    void authenticatesWithConfiguredGmailSender() throws Exception {
        var config = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("config/application-local.properties"))) {
            config.load(reader);
        }
        var sender = new JavaMailSenderImpl();
        sender.setHost("smtp.gmail.com");
        sender.setPort(587);
        sender.setUsername(config.getProperty("spring.mail.username"));
        sender.setPassword(config.getProperty("spring.mail.password"));
        var smtp = sender.getJavaMailProperties();
        smtp.setProperty("mail.smtp.auth", "true");
        smtp.setProperty("mail.smtp.starttls.enable", "true");
        smtp.setProperty("mail.smtp.starttls.required", "true");
        smtp.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        smtp.setProperty("mail.smtp.connectiontimeout", "5000");
        smtp.setProperty("mail.smtp.timeout", "5000");
        smtp.setProperty("mail.smtp.writetimeout", "5000");
        sender.testConnection();
    }
}
