package lk.sunshine.smartcare.features.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TextAlertWriterTest {
    @TempDir Path directory;
    private final NotificationRepository repo=mock(NotificationRepository.class);

    private Map<String,Object> alert(long id,String type,String preference,String status) {
        return Map.of("id",id,"appointment_id",12L,"type",type,"message","SmartCare: appointment #12 at Main Branch on Tuesday.","created_at",Timestamp.valueOf(LocalDateTime.of(2026,9,14,10,0)),"phone","0771234567","contact_preference",preference,"appointment_status",status);
    }

    @Test void optedInPatientGetsSeparateFilesForEachAppointmentEvent() throws Exception {
        when(repo.pendingTextAlerts()).thenReturn(List.of(alert(1,"BOOKED","IN_APP_AND_TEXT","CONFIRMED"),alert(2,"RESCHEDULED","IN_APP_AND_TEXT","CONFIRMED"),alert(3,"CANCELLED","IN_APP_AND_TEXT","CANCELLED"),alert(4,"REMINDER","IN_APP_AND_TEXT","CONFIRMED")));
        new TextAlertWriter(repo,directory.toString()).writeDueAlerts();
        try(var files=Files.list(directory)) { assertThat(files.filter(p->p.toString().endsWith(".txt")).count()).isEqualTo(4); }
        assertThat(Files.readString(directory.resolve("notification-1.txt"))).contains("To: 0771234567","Appointment: #12","Event: BOOKED","Created (UTC):","No SMS was sent.");
        verify(repo,times(4)).markTextHandled(anyLong());
    }

    @Test void optOutAndInactiveReminderDoNotCreateFiles() throws Exception {
        when(repo.pendingTextAlerts()).thenReturn(List.of(alert(5,"BOOKED","IN_APP","CONFIRMED"),alert(6,"REMINDER","IN_APP_AND_TEXT","CANCELLED")));
        new TextAlertWriter(repo,directory.toString()).writeDueAlerts();
        try(var files=Files.list(directory)) { assertThat(files).isEmpty(); }
        verify(repo).markTextHandled(5);
        verify(repo).markTextHandled(6);
    }

    @Test void restartFindsExistingFileAndMarksNotificationWithoutRewriting() throws Exception {
        TextAlertWriter writer=new TextAlertWriter(repo,directory.toString());
        when(repo.pendingTextAlerts()).thenReturn(List.of(alert(7,"REMINDER","IN_APP_AND_TEXT","CONFIRMED")));
        writer.writeDueAlerts();
        Path file=directory.resolve("notification-7.txt");
        Files.writeString(file,"original file");
        new TextAlertWriter(repo,directory.toString()).writeDueAlerts();
        assertThat(Files.readString(file)).isEqualTo("original file");
        verify(repo,times(2)).markTextHandled(7);
    }
}
