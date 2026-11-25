package lk.sunshine.smartcare.features.notification;

import lk.sunshine.smartcare.shared.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class NotificationServiceTest {
    private final NotificationRepository repo=mock(NotificationRepository.class);
    private final NotificationService service=new NotificationService(repo,mock(CurrentUser.class));

    private Map<String,Object> appointment() {
        Map<String,Object> row=new HashMap<>();
        row.put("user_id",3L);row.put("doctor_user_id",7L);
        row.put("doctor_name","Perera");row.put("patient_name","Nimali");row.put("branch_name","Main Branch");
        row.put("status","CONFIRMED");
        row.put("starts_at",Timestamp.valueOf(LocalDateTime.now().plusDays(3)));
        return row;
    }

    @Test void pendingBookingAlertsPatientAndDoctorWithoutSchedulingReminders() {
        when(repo.appointment(12)).thenReturn(appointment());
        service.onAppointment(new AppointmentEvent(12,"BOOKED"));
        verify(repo).add(eq(3L),eq(12L),eq("BOOKED"),contains("Dr. Perera"),isNull());
        verify(repo).add(eq(7L),eq(12L),eq("BOOKED"),contains("Nimali"),isNull());
        verify(repo,never()).add(eq(3L),eq(12L),eq("REMINDER"),anyString(),any());
    }

    @Test void confirmationSchedulesTwoReminders() {
        when(repo.appointment(12)).thenReturn(appointment());
        service.onAppointment(new AppointmentEvent(12,"CONFIRMED"));
        verify(repo).add(eq(3L),eq(12L),eq("CONFIRMED"),contains("Dr. Perera"),isNull());
        verify(repo).add(eq(3L),eq(12L),eq("REMINDER"),contains("Appointment reminder"),any(LocalDateTime.class));
        verify(repo).add(eq(3L),eq(12L),eq("REMINDER"),contains("two hours"),any(LocalDateTime.class));
    }

    @Test void bookingWithinTwoHoursShowsOneImmediateReminder() {
        Map<String,Object> row=appointment();
        row.put("starts_at",Timestamp.valueOf(LocalDateTime.now().plusHours(1)));
        when(repo.appointment(12)).thenReturn(row);
        service.onAppointment(new AppointmentEvent(12,"CONFIRMED"));
        verify(repo).add(eq(3L),eq(12L),eq("REMINDER"),contains("Appointment reminder"),isNull());
        verify(repo,never()).add(eq(3L),eq(12L),eq("REMINDER"),contains("two hours"),any());
    }

    @Test void rescheduleReplacesReminderAndCancellationRemovesIt() {
        when(repo.appointment(12)).thenReturn(appointment());
        service.onAppointment(new AppointmentEvent(12,"RESCHEDULED"));
        verify(repo).removePendingReminder(12);
        verify(repo).add(eq(3L),eq(12L),eq("RESCHEDULED"),contains("Schedule changed"),isNull());
        verify(repo).add(eq(7L),eq(12L),eq("RESCHEDULED"),contains("rescheduled"),isNull());
        clearInvocations(repo);
        service.onAppointment(new AppointmentEvent(12,"CANCELLED"));
        verify(repo).removePendingReminder(12);
        verify(repo).add(eq(3L),eq(12L),eq("CANCELLED"),contains("cancelled"),isNull());
        verify(repo).add(eq(7L),eq(12L),eq("CANCELLED"),contains("cancelled"),isNull());
        verify(repo,never()).add(eq(3L),eq(12L),eq("REMINDER"),anyString(),any());
    }

    @Test void doctorScheduleChangeOnlyAlertsThatDoctor() {
        when(repo.doctorUser(5)).thenReturn(7L);
        service.onSchedule(new ScheduleEvent(5,"Weekly schedule changed"));
        verify(repo).doctorUser(5);
        verify(repo).add(7L,null,"SCHEDULE_CHANGED","Weekly schedule changed",null);
        verifyNoMoreInteractions(repo);
    }

    @Test void inboxAndReadUseTheSignedInUserId() {
        Authentication auth=mock(Authentication.class);
        CurrentUser users=mock(CurrentUser.class);
        NotificationService ownService=new NotificationService(repo,users);
        when(users.id(auth)).thenReturn(3L);
        ownService.inbox(auth);
        verify(repo).inbox(3L);
        ownService.upcoming(auth);
        verify(repo).upcoming(3L);
        when(repo.markRead(3L,21L)).thenReturn(0);
        assertThatThrownBy(() -> ownService.read(auth,21L)).hasMessageContaining("Notification not found");
        verify(repo).markRead(3L,21L);
    }

    @Test void aNotificationStorageFailureDoesNotReportACommittedBookingAsFailed() {
        when(repo.appointment(12)).thenThrow(new IllegalStateException("Database alert error"));
        assertThatCode(() -> service.onAppointment(new AppointmentEvent(12,"BOOKED"))).doesNotThrowAnyException();
    }

    @Test void overdueRemindersAreExpiredBeforeDueOnesAreReleased() {
        service.releaseDueReminders();
        var order=inOrder(repo);
        order.verify(repo).expirePendingReminders();
        order.verify(repo).releaseReminders();
    }
}
