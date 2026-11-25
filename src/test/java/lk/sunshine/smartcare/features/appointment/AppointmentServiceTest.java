package lk.sunshine.smartcare.features.appointment;

import lk.sunshine.smartcare.shared.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AppointmentServiceTest {
    private final AppointmentRepository repo=mock(AppointmentRepository.class);
    private final CurrentUser users=mock(CurrentUser.class);
    private final ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);
    private final Authentication auth=mock(Authentication.class);
    private final AppointmentService service=new AppointmentService(repo,users,events);

    @Test void bookedSlotCannotBeClaimedByAnotherPatient() {
        when(users.role(auth)).thenReturn("PATIENT");
        when(repo.claimSlot(42)).thenReturn(0);
        assertThatThrownBy(() -> service.book(auth,42)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("no longer available");
        verify(repo,never()).create(anyLong(),anyLong());
        verifyNoInteractions(events);
    }

    @Test void successfulClaimCreatesOnlyOneAppointment() {
        when(users.role(auth)).thenReturn("PATIENT");when(users.id(auth)).thenReturn(7L);
        when(repo.claimSlot(42)).thenReturn(1);when(repo.patientId(7L)).thenReturn(3L);when(repo.create(3L,42L)).thenReturn(9L);
        org.assertj.core.api.Assertions.assertThat(service.book(auth,42)).isEqualTo(9L);
        verify(repo).create(3L,42L);
        verify(events).publishEvent(any(lk.sunshine.smartcare.features.notification.AppointmentEvent.class));
    }

    @Test void pastAppointmentCannotBeChangedOrPaid() {
        when(users.role(auth)).thenReturn("PATIENT");
        when(users.id(auth)).thenReturn(7L);
        when(repo.one(12L)).thenReturn(Map.of("user_id",7L,"status","CONFIRMED",
                "starts_at",Timestamp.valueOf(LocalDateTime.now().minusDays(1))));

        assertThatThrownBy(() -> service.reschedule(auth,12L,42L))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("upcoming pending or confirmed");
        assertThatThrownBy(() -> service.cancel(auth,12L))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("upcoming pending or confirmed");
        assertThatThrownBy(() -> service.payment(auth,12L,"DEMO_PAID"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("upcoming pending or confirmed");
        verify(repo,never()).changeSlot(anyLong(),anyLong());
        verify(repo,never()).cancel(anyLong());
        verify(repo,never()).payment(anyLong(),anyDouble(),anyString(),anyString());
    }

    @Test void receptionCanBookForRegisteredPatientButDoctorCannot() {
        when(users.role(auth)).thenReturn("RECEPTION");
        when(repo.patientExists(3L)).thenReturn(1);
        when(repo.claimSlot(42L)).thenReturn(1);
        when(repo.create(3L,42L)).thenReturn(15L);
        org.assertj.core.api.Assertions.assertThat(service.bookForPatient(auth,3L,42L)).isEqualTo(15L);
        verify(events).publishEvent(any(lk.sunshine.smartcare.features.notification.AppointmentEvent.class));

        clearInvocations(repo,events);
        when(users.role(auth)).thenReturn("DOCTOR");
        assertThatThrownBy(() -> service.bookForPatient(auth,3L,42L)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repo,events);
    }
    @Test void onlyAssignedDoctorCanChangeUpcomingAppointment() {
        when(users.role(auth)).thenReturn("DOCTOR");when(users.id(auth)).thenReturn(9L);
        when(repo.one(20L)).thenReturn(Map.of("doctor_user_id",9L,"user_id",7L,"doctor_id",5L,"slot_id",30L,"status","CONFIRMED",
                "starts_at",Timestamp.valueOf(LocalDateTime.now().plusDays(2))));
        when(repo.slotDoctor(31L)).thenReturn(5L);when(repo.claimSlot(31L)).thenReturn(1);
        service.reschedule(auth,20L,31L);
        verify(repo).changeSlot(20L,31L);
        verify(repo).releaseSlot(30L);

        when(users.id(auth)).thenReturn(10L);
        assertThatThrownBy(() -> service.cancel(auth,20L))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("not assigned");
        verify(repo,never()).cancel(20L);
    }
    @Test void bookingSavesSelectedPaymentMethodForAdminBeforeSubmission() {
        when(users.role(auth)).thenReturn("PATIENT");when(users.id(auth)).thenReturn(7L);
        when(repo.slotBranch(42L)).thenReturn(2L);when(repo.claimSlot(42L)).thenReturn(1);
        when(repo.patientId(7L)).thenReturn(3L);when(repo.create(3L,42L)).thenReturn(21L);
        when(repo.one(21L)).thenReturn(Map.of("fee",1500));
        org.assertj.core.api.Assertions.assertThat(service.book(auth,42L,2L,"DEMO_CARD")).isEqualTo(21L);
        verify(repo).payment(21L,1500.0,"DEMO_CARD","PENDING_DEMO");

        when(users.role(auth)).thenReturn("ADMIN");
        when(repo.listForAdmin()).thenReturn(List.of(Map.of("id",21L,"payment_status","PENDING_DEMO")));
        org.assertj.core.api.Assertions.assertThat(service.list(auth)).hasSize(1);
        verify(repo).listForAdmin();verify(repo,never()).listForStaff();
    }
    @Test void aSubmittedDemoPaymentCannotBeSubmittedTwice() {
        when(users.role(auth)).thenReturn("PATIENT");when(users.id(auth)).thenReturn(7L);
        when(repo.one(21L)).thenReturn(Map.of("user_id",7L,"status","CONFIRMED","starts_at",Timestamp.valueOf(LocalDateTime.now().plusDays(1))));
        when(repo.paymentStatus(21L)).thenReturn("DEMO_PAID");
        assertThatThrownBy(() -> service.payment(auth,21L,"DEMO_PAID"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("already recorded");
        verify(repo,never()).payment(anyLong(),anyDouble(),anyString(),anyString());
    }

    @Test void unlinkedDoctorCannotChangeAnAppointment() {
        when(users.role(auth)).thenReturn("DOCTOR");when(users.id(auth)).thenReturn(9L);
        when(repo.one(20L)).thenReturn(Map.of("status","CONFIRMED","user_id",7L));
        assertThatThrownBy(() -> service.cancel(auth,20L))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("not assigned");
        verify(repo,never()).cancel(20L);
    }

    @Test void onlyAdministratorCanConfirmPendingAppointment() {
        when(users.role(auth)).thenReturn("ADMIN");when(repo.confirm(20L)).thenReturn(1);
        service.confirm(auth,20L);
        verify(users).audit(auth,"CONFIRM_APPOINTMENT","appointment",20L);
        verify(events).publishEvent(any(lk.sunshine.smartcare.features.notification.AppointmentEvent.class));

        clearInvocations(repo,users,events);when(users.role(auth)).thenReturn("RECEPTION");
        assertThatThrownBy(() -> service.confirm(auth,20L)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Administrator");
        verify(repo,never()).confirm(anyLong());
    }

    @Test void administratorCanReschedulePendingAppointmentWithSameDoctorAndBranch() {
        when(users.role(auth)).thenReturn("ADMIN");
        when(repo.one(20L)).thenReturn(Map.of("doctor_id",5L,"slot_id",30L,"branch_id",2L,"status","PENDING","starts_at",Timestamp.valueOf(LocalDateTime.now().plusDays(2))));
        when(repo.slotDoctor(31L)).thenReturn(5L);when(repo.slotBranch(31L)).thenReturn(2L);when(repo.claimSlot(31L)).thenReturn(1);
        service.reschedule(auth,20L,31L);
        verify(repo).changeSlot(20L,31L);verify(repo).releaseSlot(30L);
    }
}
