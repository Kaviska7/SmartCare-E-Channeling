package lk.sunshine.smartcare.features.queue;

import lk.sunshine.smartcare.features.appointment.AppointmentRepository;
import lk.sunshine.smartcare.features.notification.AppointmentEvent;
import lk.sunshine.smartcare.shared.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueueServiceTest {
    private final QueueRepository repo=mock(QueueRepository.class);
    private final AppointmentRepository appointments=mock(AppointmentRepository.class);
    private final CurrentUser users=mock(CurrentUser.class);
    private final ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);
    private final Authentication auth=mock(Authentication.class);
    private final LocalDate date=LocalDate.of(2026,10,5);
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-04T19:00:00Z"),ZoneOffset.UTC);
    private final QueueService service=new QueueService(repo,appointments,users,events,clock);
    private Map<String,Object> appointment(LocalDate day) {
        return Map.of("id",25L,"slot_id",30L,"doctor_id",10L,"branch_id",2L,"appointment_date",day,"status","CHECKED_IN");
    }
    private Map<String,Object> token(String status,LocalDate day) {
        return Map.of("id",5L,"appointment_id",25L,"doctor_id",10L,"branch_id",2L,"queue_date",day,"status",status);
    }
    private void visit(String status) {
        when(repo.token(5L)).thenReturn(token(status,date));
        when(repo.lockedToken(5L)).thenReturn(token(status,date));
        when(repo.lockedAppointment(25L)).thenReturn(appointment(date));
    }
    @BeforeEach void staff() { when(users.role(auth)).thenReturn("RECEPTION"); }

    @Test void checkInUsesSriLankaDayAndDoctorLock() {
        when(repo.appointment(25L)).thenReturn(appointment(date));
        when(repo.lockedAppointment(25L)).thenReturn(appointment(date));
        when(repo.markCheckedIn(25L)).thenReturn(1);
        when(repo.nextNumber(10L,date)).thenReturn(3);
        assertThat(service.checkIn(auth,25L)).isEqualTo(3);
        var order=inOrder(repo);
        order.verify(repo).appointment(25L);
        order.verify(repo).lockDoctor(10L);
        order.verify(repo).lockedAppointment(25L);
        order.verify(repo).markCheckedIn(25L);
        order.verify(repo).nextNumber(10L,date);
        order.verify(repo).add(25L,10L,3,date);
    }
    @Test void checkInRejectsPastAndFutureDates() {
        for(LocalDate day:List.of(date.minusDays(1),date.plusDays(1))) {
            when(repo.appointment(25L)).thenReturn(appointment(day));
            when(repo.lockedAppointment(25L)).thenReturn(appointment(day));
            assertThatThrownBy(()->service.checkIn(auth,25L)).isInstanceOf(ResponseStatusException.class);
        }
        verify(repo,never()).markCheckedIn(anyLong());
    }
    @Test void checkInRejectsDuplicateOrUnconfirmedAppointment() {
        when(repo.appointment(25L)).thenReturn(appointment(date));
        when(repo.lockedAppointment(25L)).thenReturn(appointment(date));
        assertThatThrownBy(()->service.checkIn(auth,25L)).hasMessageContaining("not confirmed");
        verify(repo,never()).add(anyLong(),anyLong(),anyInt(),any());
    }
    @Test void callNextSelectsOnlyRequestedBranchAndPublishesDoctorEvent() {
        visit("WAITING");
        when(repo.nextWaiting(10L,2L,date)).thenReturn(5L);
        when(repo.transition(5L,"WAITING","CALLED")).thenReturn(1);
        assertThat(service.callNext(auth,10L,2L,date)).isEqualTo(5L);
        verify(repo).appointmentStatus(25L,"CALLED");
        verify(events).publishEvent(new AppointmentEvent(25L,"CALLED"));
    }
    @Test void doctorCannotHaveTwoCalledPatientsAcrossBranches() {
        when(repo.activeCalled(10L,date)).thenReturn(1);
        assertThatThrownBy(()->service.callNext(auth,10L,2L,date)).hasMessageContaining("Finish the current token");
        verify(repo,never()).nextWaiting(anyLong(),anyLong(),any());
    }
    @Test void emptyQueueReturnsNoToken() {
        when(repo.nextWaiting(10L,2L,date)).thenReturn(null);
        assertThat(service.callNext(auth,10L,2L,date)).isNull();
    }
    @Test void historicalQueueCannotCallNext() {
        assertThatThrownBy(()->service.callNext(auth,10L,2L,date.minusDays(1))).hasMessageContaining("only for today");
        verify(repo,never()).lockDoctor(anyLong());
    }
    @Test void finishDoesNotAutomaticallyAdvanceQueue() {
        visit("CALLED");when(repo.transition(5L,"CALLED","COMPLETED")).thenReturn(1);
        service.finish(auth,5L,false);
        verify(repo).appointmentStatus(25L,"COMPLETED");
        verify(repo,never()).nextWaiting(anyLong(),anyLong(),any());
    }
    @Test void onlyCalledTokenCanFinish() {
        visit("WAITING");
        assertThatThrownBy(()->service.finish(auth,5L,false)).hasMessageContaining("Only called tokens");
        verify(repo,never()).appointmentStatus(anyLong(),anyString());
    }
    @Test void doctorCanMarkOwnCalledPatientNoShow() {
        visit("CALLED");when(users.role(auth)).thenReturn("DOCTOR");when(users.id(auth)).thenReturn(4L);
        when(repo.doctorForUser(4L)).thenReturn(10L);when(repo.transition(5L,"CALLED","SKIPPED")).thenReturn(1);
        service.finish(auth,5L,true);
        verify(repo).appointmentStatus(25L,"NO_SHOW");
        verify(events).publishEvent(new AppointmentEvent(25L,"NO_SHOW"));
        when(repo.doctorForUser(4L)).thenReturn(11L);
        assertThatThrownBy(()->service.finish(auth,5L,true)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void historicalTokenCannotBeChanged() {
        when(repo.token(5L)).thenReturn(token("WAITING",date.minusDays(1)));
        when(repo.lockedToken(5L)).thenReturn(token("WAITING",date.minusDays(1)));
        assertThatThrownBy(()->service.cancel(auth,5L)).hasMessageContaining("only for today");
        verifyNoInteractions(appointments);
    }
    @Test void cancelRetainsTokenHistoryAndReleasesSlot() {
        visit("WAITING");when(repo.transition(5L,"WAITING","CANCELLED")).thenReturn(1);
        service.cancel(auth,5L);
        verify(appointments).cancel(25L);verify(appointments).releaseSlot(30L);
        verify(users).audit(auth,"CANCEL_QUEUE_VISIT","appointment",25L);
        verify(events).publishEvent(new AppointmentEvent(25L,"CANCELLED"));
    }
    @Test void calledTokenCannotBeDeletedOrRescheduled() {
        visit("CALLED");
        assertThatThrownBy(()->service.cancel(auth,5L)).hasMessageContaining("Only waiting");
        assertThatThrownBy(()->service.reschedule(auth,5L,31L)).hasMessageContaining("Only waiting");
        verifyNoInteractions(appointments);
    }
    @Test void rescheduleRetiresTokenAndRequiresFreshCheckIn() {
        visit("WAITING");when(appointments.slotDoctor(31L)).thenReturn(10L);
        when(appointments.slotBranch(31L)).thenReturn(2L);when(appointments.claimSlot(31L)).thenReturn(1);
        when(repo.transition(5L,"WAITING","RESCHEDULED")).thenReturn(1);
        service.reschedule(auth,5L,31L);
        verify(appointments).changeSlot(25L,31L);verify(appointments).releaseSlot(30L);
        verify(repo).appointmentStatus(25L,"CONFIRMED");
        verify(events).publishEvent(new AppointmentEvent(25L,"RESCHEDULED"));
    }
    @Test void wrongBranchAndTakenSlotDoNotMoveAppointment() {
        visit("WAITING");when(appointments.slotDoctor(31L)).thenReturn(10L);
        when(appointments.slotBranch(31L)).thenReturn(3L);
        assertThatThrownBy(()->service.reschedule(auth,5L,31L)).hasMessageContaining("same doctor and branch");
        when(appointments.slotBranch(31L)).thenReturn(2L);
        assertThatThrownBy(()->service.reschedule(auth,5L,31L)).hasMessageContaining("no longer available");
        verify(appointments,never()).changeSlot(anyLong(),anyLong());
    }
    @Test void managerHasReadOnlyAccess() {
        when(users.role(auth)).thenReturn("MANAGER");
        service.list(auth,date,2L,null);verify(repo).list(date,2L,null);
        assertThatThrownBy(()->service.checkIn(auth,25L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.callNext(auth,10L,2L,date)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.cancel(auth,5L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.reschedule(auth,5L,31L)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void doctorListCannotRequestAnotherDoctor() {
        when(users.role(auth)).thenReturn("DOCTOR");when(users.id(auth)).thenReturn(4L);when(repo.doctorForUser(4L)).thenReturn(10L);
        service.list(auth,date,null,null);verify(repo).list(date,null,10L);
        assertThatThrownBy(()->service.list(auth,date,null,11L)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void searchIsTrimmedAndLimitedToToday() {
        service.findAppointments(auth,"  patient  ");verify(repo).findAppointments("patient",date);
        assertThatThrownBy(()->service.findAppointments(auth,"  ")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.findAppointments(auth,"x".repeat(81))).isInstanceOf(ResponseStatusException.class);
    }
}
