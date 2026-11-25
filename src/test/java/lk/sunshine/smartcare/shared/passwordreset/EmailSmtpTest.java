package lk.sunshine.smartcare.shared.passwordreset;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.env.MockEnvironment;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EmailSmtpTest {
    @Test
    void deliversLeadingZeroOtpWithPlainTextAndHtmlOverSmtp() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            var received = executor.submit(() -> receive(server));
            var sender = new JavaMailSenderImpl();
            sender.setHost(server.getInetAddress().getHostAddress());
            sender.setPort(server.getLocalPort());
            sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "5000");
            sender.getJavaMailProperties().setProperty("mail.smtp.timeout", "5000");
            sender.getJavaMailProperties().setProperty("mail.smtp.writetimeout", "5000");

            new EmailService(sender, new MockEnvironment(), "smartcare.sender@gmail.com", false)
                    .sendResetCode("recipient@example.com", "000042");

            var message = received.get(10, TimeUnit.SECONDS);
            assertThat(message.getFrom()[0].toString()).isEqualTo("smartcare.sender@gmail.com");
            assertThat(message.getAllRecipients()[0].toString()).isEqualTo("recipient@example.com");
            assertThat(message.getSubject()).isEqualTo("Your SmartCare password reset code");
            var plain = new ArrayList<String>();
            var html = new ArrayList<String>();
            collectAlternatives(message, plain, html);
            assertThat(plain).singleElement().asString().contains("000042", "10 minutes");
            assertThat(html).singleElement().asString().contains("000042", "10 minutes", "<h2>");
        } finally {
            executor.shutdownNow();
        }
    }

    private MimeMessage receive(ServerSocket server) throws Exception {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(5000);
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
            reply(writer, "220 localhost test SMTP");
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("EHLO") || line.startsWith("HELO")) {
                    reply(writer, "250 localhost");
                } else if (line.equals("DATA")) {
                    reply(writer, "354 End with a dot");
                    while ((line = reader.readLine()) != null && !line.equals(".")) {
                        data.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                    }
                    reply(writer, "250 Accepted");
                } else if (line.equals("QUIT")) {
                    reply(writer, "221 Bye");
                    break;
                } else {
                    reply(writer, "250 OK");
                }
            }
            return new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(data.toString().getBytes(StandardCharsets.US_ASCII)));
        }
    }

    private void reply(BufferedWriter writer, String text) throws IOException {
        writer.write(text + "\r\n");
        writer.flush();
    }

    private void collectAlternatives(Part part, List<String> plain, List<String> html) throws Exception {
        if (part.isMimeType("multipart/*")) {
            var multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) collectAlternatives(multipart.getBodyPart(i), plain, html);
        } else if (part.isMimeType("text/plain")) {
            plain.add((String) part.getContent());
        } else if (part.isMimeType("text/html")) {
            html.add((String) part.getContent());
        }
    }
}
