package lk.sunshine.smartcare.features.doctor;

import lk.sunshine.smartcare.shared.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class DoctorServiceTest {
    private final DoctorRepository repo=mock(DoctorRepository.class);
    private final CurrentUser users=mock(CurrentUser.class);
    private final Authentication auth=mock(Authentication.class);
    private final DoctorService service=new DoctorService(repo,users,mock(PasswordEncoder.class),mock(ApplicationEventPublisher.class));

    @Test void overlappingSpecificDateScheduleIsRejectedBeforeSlotsAreCreated() {
        LocalDate date=LocalDate.now().plusDays(3);
        when(users.role(auth)).thenReturn("ADMIN");when(repo.overlap(eq(5L),eq(date),any(),any())).thenReturn(1);
        when(repo.activeBranchAssignment(5L,2L)).thenReturn(1);when(repo.branchAssignment(5L,2L)).thenReturn(Map.of("room","12"));
        var input=new DoctorController.ScheduleInput(date,LocalTime.of(9,0),LocalTime.of(12,0),30,6,2L);
        assertThatThrownBy(() -> service.addSchedule(auth,5L,input)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("overlaps");
        verify(repo,never()).addSchedule(anyLong(),anyLong(),any(),any(),any(),anyInt(),anyInt());
    }
    @Test void specificDateScheduleCreatesSlotsOnlyOnThatDate() {
        LocalDate date=LocalDate.now().plusDays(4);
        when(users.role(auth)).thenReturn("ADMIN");when(repo.activeBranchAssignment(5L,2L)).thenReturn(1);
        when(repo.branchAssignment(5L,2L)).thenReturn(Map.of("room","12"));when(repo.addSchedule(5L,2L,date,LocalTime.of(9,0),LocalTime.of(11,0),30,2)).thenReturn(9L);
        long id=service.addSchedule(auth,5L,new DoctorController.ScheduleInput(date,LocalTime.of(9,0),LocalTime.of(11,0),30,2,2L));
        org.assertj.core.api.Assertions.assertThat(id).isEqualTo(9L);
        verify(repo).createSlot(5L,2L,date.atTime(9,0),date.atTime(9,30));
        verify(repo).createSlot(5L,2L,date.atTime(9,30),date.atTime(10,0));
        verify(repo,times(2)).createSlot(eq(5L),eq(2L),any(LocalDateTime.class),any(LocalDateTime.class));
    }
    @Test void pastAndLeaveDatesAreRejected() {
        when(users.role(auth)).thenReturn("ADMIN");
        var past=new DoctorController.ScheduleInput(LocalDate.now().minusDays(1),LocalTime.of(9,0),LocalTime.of(10,0),30,2,2L);
        assertThatThrownBy(() -> service.addSchedule(auth,5L,past)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("today or later");
        LocalDate leave=LocalDate.now().plusDays(2);when(repo.leaveCount(5L,leave)).thenReturn(1);
        var away=new DoctorController.ScheduleInput(leave,LocalTime.of(9,0),LocalTime.of(10,0),30,2,2L);
        assertThatThrownBy(() -> service.addSchedule(auth,5L,away)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("on leave");
        verify(repo,never()).createSlot(anyLong(),anyLong(),any(),any());
    }
    @Test void doctorLeaveCannotHideExistingBookingsOrAnotherDoctorsCalendar() {
        LocalDate tomorrow=LocalDate.now().plusDays(1);
        when(users.role(auth)).thenReturn("DOCTOR");when(users.id(auth)).thenReturn(7L);
        when(repo.doctor(5L)).thenReturn(Map.of("user_id",8L));
        assertThatThrownBy(() -> service.addLeave(auth,5L,tomorrow,"Away"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Doctor access required");
        verify(repo,never()).retireOpenSlotsOnDate(anyLong(),any());

        when(repo.doctor(5L)).thenReturn(Map.of("user_id",7L));
        when(repo.bookedOnDate(5L,tomorrow)).thenReturn(1);
        assertThatThrownBy(() -> service.addLeave(auth,5L,tomorrow,"Away"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Existing appointments");
        verify(repo,never()).addLeave(anyLong(),any(),any());
    }
    @Test void doctorCanUpdateEveryOwnProfileFieldExceptRegistrationId() {
        when(users.role(auth)).thenReturn("DOCTOR");when(users.id(auth)).thenReturn(7L);
        when(repo.mine(7L)).thenReturn(Map.of("id",5L,"user_id",7L,"email","old@gmail.com","registration_id","SLMC/100"));
        when(repo.branchExists(2L)).thenReturn(1);when(repo.branches(5L)).thenReturn(List.of(Map.of("branch_id",2L)));
        boolean emailChanged=service.updateMine(auth,new DoctorController.DoctorUpdateInput("Dr Silva","new@gmail.com",3L,"Cardiology",false,List.of(new DoctorController.BranchAssignmentInput(2L,"12",3000))));
        org.assertj.core.api.Assertions.assertThat(emailChanged).isTrue();
        verify(repo).updateDoctor(5L,"Dr Silva",3L,"Cardiology",false);
        verify(repo).updateDoctorAccount(7L,"Dr Silva","new@gmail.com");
        verify(repo).upsertBranch(5L,2L,"12",3000);
        verify(repo,never()).registrationCount(anyString(),any());
        verify(users).audit(auth,"UPDATE_DOCTOR_PROFILE","doctor",5L);
    }
    @Test void administratorCreatesDoctorWithNormalizedUniqueRegistrationId() {
        when(users.role(auth)).thenReturn("ADMIN");when(repo.userRole(7L)).thenReturn("DOCTOR");when(repo.branchExists(2L)).thenReturn(1);
        when(repo.createDoctor(7L,"Dr Silva","SLMC/123-AB",3L,"Cardiology")).thenReturn(5L);
        var input=new DoctorController.DoctorInput(7L," Dr Silva "," slmc/123-ab ",3L," Cardiology ",true,List.of(new DoctorController.BranchAssignmentInput(2L,"",2500)));
        service.createDoctor(auth,input);
        verify(repo).registrationCount("SLMC/123-AB",null);
        verify(repo).createDoctor(7L,"Dr Silva","SLMC/123-AB",3L,"Cardiology");verify(repo).upsertBranch(5L,2L,null,2500);
    }
    @Test void duplicateOrMalformedRegistrationIdIsRejected() {
        when(users.role(auth)).thenReturn("ADMIN");when(repo.userRole(7L)).thenReturn("DOCTOR");when(repo.registrationCount("SLMC/123",null)).thenReturn(1);
        var duplicate=new DoctorController.DoctorInput(7L,"Dr Silva","slmc/123",3L,"Cardiology",true,List.of(new DoctorController.BranchAssignmentInput(2L,"1",2500)));
        assertThatThrownBy(() -> service.createDoctor(auth,duplicate)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("already belongs");
        var malformed=new DoctorController.DoctorInput(7L,"Dr Silva","MB BS 123",3L,"Cardiology",true,List.of(new DoctorController.BranchAssignmentInput(2L,"1",2500)));
        assertThatThrownBy(() -> service.createDoctor(auth,malformed)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("3-50");
        verify(repo,never()).createDoctor(any(),any(),any(),anyLong(),any());
    }
    @Test void administratorCanUpdateLegacyDoctorWithoutChangingRegistrationIdOrEmail() {
        when(users.role(auth)).thenReturn("ADMIN");when(repo.doctor(5L)).thenReturn(Map.of("user_id",7L,"email","legacy@hospital.local","registration_id","SLMC/100"));when(repo.branchExists(2L)).thenReturn(1);when(repo.branches(5L)).thenReturn(List.of(Map.of("branch_id",2L)));
        var input=new DoctorController.DoctorUpdateInput("Dr Legacy","legacy@hospital.local",3L,"Medicine",true,List.of(new DoctorController.BranchAssignmentInput(2L,"2",1800)));
        service.updateDoctor(auth,5L,input);
        verify(repo).updateDoctor(5L,"Dr Legacy",3L,"Medicine",true);verify(repo).updateDoctorAccount(7L,"Dr Legacy","legacy@hospital.local");verify(repo).upsertBranch(5L,2L,"2",1800);
        verify(repo,never()).registrationCount(anyString(),any());
    }
    @Test void doctorEmailMustBeGmailAndUniqueAcrossAllAccounts() {
        when(users.role(auth)).thenReturn("ADMIN");when(repo.doctor(5L)).thenReturn(Map.of("user_id",7L,"email","old@gmail.com"));
        var branches=List.of(new DoctorController.BranchAssignmentInput(2L,"2",1800));
        assertThatThrownBy(() -> service.updateDoctor(auth,5L,new DoctorController.DoctorUpdateInput("Doctor","new@example.com",3L,"Medicine",true,branches)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("valid @gmail.com");
        when(repo.emailCountExcept("used@gmail.com",7L)).thenReturn(1);
        assertThatThrownBy(() -> service.updateDoctor(auth,5L,new DoctorController.DoctorUpdateInput("Doctor","USED@GMAIL.COM",3L,"Medicine",true,branches)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("already in use");
        verify(repo,never()).updateDoctor(anyLong(),anyString(),anyLong(),anyString(),anyBoolean());
    }
    @Test void administratorCanDeleteOnlyUnusedDoctorProfile() {
        when(users.role(auth)).thenReturn("ADMIN");when(repo.doctor(5L)).thenReturn(Map.of("user_id",7L));
        assertThatCode(() -> service.deleteDoctor(auth,5L)).doesNotThrowAnyException();
        verify(repo).deleteInactiveSchedules(5L);verify(repo).deleteRetiredUnbookedSlots(5L);
        verify(repo).anonymizeAuditEvents(7L);verify(repo).deleteNotifications(7L);verify(repo).deleteDoctor(5L);verify(repo).deleteUser(7L);

        when(repo.doctor(6L)).thenReturn(Map.of("user_id",8L));when(repo.dependencyCount(6L)).thenReturn(1);
        assertThatThrownBy(() -> service.deleteDoctor(auth,6L)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("cannot be deleted");
        verify(repo).deleteInactiveSchedules(6L);verify(repo).deleteRetiredUnbookedSlots(6L);
        verify(repo,never()).anonymizeAuditEvents(8L);verify(repo,never()).deleteDoctor(6L);
    }
    @Test void notificationsAndAuditHistoryDoNotBlockDoctorDeletion() {
        when(users.role(auth)).thenReturn("ADMIN");when(repo.doctor(5L)).thenReturn(Map.of("user_id",7L));
        when(repo.dependencyCount(5L)).thenReturn(0);
        service.deleteDoctor(auth,5L);
        verify(repo).anonymizeAuditEvents(7L);verify(repo).deleteNotifications(7L);

        when(repo.doctor(6L)).thenReturn(Map.of("user_id",8L));when(repo.dependencyCount(6L)).thenReturn(2);
        assertThatThrownBy(() -> service.deleteDoctor(auth,6L)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("appointment or slot records");
        verify(repo,never()).anonymizeAuditEvents(8L);verify(repo,never()).deleteNotifications(8L);
    }
    @Test void nonAdministratorCannotDeleteDoctorProfile() {
        when(users.role(auth)).thenReturn("DOCTOR");
        assertThatThrownBy(() -> service.deleteDoctor(auth,5L)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Administrator");
        verifyNoInteractions(repo);
    }
}
