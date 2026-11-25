package lk.sunshine.smartcare.features.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

@Service
public class TextAlertWriter {
    private static final Logger log=LoggerFactory.getLogger(TextAlertWriter.class);
    private final NotificationRepository repo;
    private final Path directory;

    public TextAlertWriter(NotificationRepository repo,@Value("${smartcare.message-preview.directory:config/message-preview}") String directory) {
        this.repo=repo;
        this.directory=Path.of(directory).toAbsolutePath().normalize();
    }

    @Scheduled(fixedDelay=10000)
    public void writeDueAlerts() {
        for(Map<String,Object> alert:repo.pendingTextAlerts()) {
            long id=((Number)alert.get("id")).longValue();
            try {
                boolean optedIn="IN_APP_AND_TEXT".equals(alert.get("contact_preference"));
                boolean validReminder=!"REMINDER".equals(alert.get("type")) || "CONFIRMED".equals(alert.get("appointment_status"));
                if(optedIn && validReminder)writeFile(alert,id);
                repo.markTextHandled(id);
            } catch(Exception e) {
                log.warn("Could not create local text alert for notification {}: {}",id,e.getClass().getSimpleName());
            }
        }
    }

    private void writeFile(Map<String,Object> alert,long id) throws Exception {
        Files.createDirectories(directory);
        Path destination=directory.resolve("notification-"+id+".txt");
        if(Files.exists(destination))return;
        String message=String.valueOf(alert.get("message")).replaceAll("[\\r\\n]+"," ");
        String contents="SmartCare appointment alert (local demo)\n"
                +"To: "+alert.get("phone")+"\n"
                +"Appointment: #"+alert.get("appointment_id")+"\n"
                +"Event: "+alert.get("type")+"\n"
                +"Created (UTC): "+alert.get("created_at")+"\n"
                +"Message: "+message+"\n"
                +"No SMS was sent.\n";
        Path temporary=directory.resolve("notification-"+id+"-"+UUID.randomUUID()+".tmp");
        try {
            Files.writeString(temporary,contents,StandardCharsets.UTF_8);
            try { Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException e) { Files.move(temporary,destination); }
            catch(FileAlreadyExistsException ignored) { /* A previous run already wrote this notification. */ }
        } finally { Files.deleteIfExists(temporary); }
    }
}
