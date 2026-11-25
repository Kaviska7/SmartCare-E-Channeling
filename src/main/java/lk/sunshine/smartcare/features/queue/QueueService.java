package lk.sunshine.smartcare.features.queue;

import lk.sunshine.smartcare.features.appointment.AppointmentRepository;
import lk.sunshine.smartcare.features.notification.AppointmentEvent;
import lk.sunshine.smartcare.shared.CurrentUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
public class QueueService {
    private final QueueRepository repo;
    private final AppointmentRepository appointments;
    private final CurrentUser users;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    public QueueService(QueueRepository repo,AppointmentRepository appointments,CurrentUser users,ApplicationEventPublisher events,Clock clock) {
        this.repo=repo;this.appointments=appointments;this.users=users;this.events=events;this.clock=clock;
    }
    private LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("Asia/Colombo"))); }
    private LocalDate day(Object value) { return value instanceof java.sql.Date d?d.toLocalDate():(LocalDate)value; }
    private long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private void currentDay(LocalDate date) { if(!today().equals(date))throw conflict("Live queue actions are available only for today"); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }

    @Transactional public int checkIn(Authentication auth,long appointmentId) {
        reception(auth);Map<String,Object> initial=repo.appointment(appointmentId);
        long doctorId=number(initial,"doctor_id");repo.lockDoctor(doctorId);
        Map<String,Object> appointment=repo.lockedAppointment(appointmentId);
        if(number(appointment,"doctor_id")!=doctorId)throw conflict("Appointment changed; refresh the queue");
        currentDay(day(appointment.get("appointment_date")));
        if(repo.markCheckedIn(appointmentId)!=1)throw conflict("Appointment is not confirmed or was already checked in");
        int token=repo.nextNumber(doctorId,today());repo.add(appointmentId,doctorId,token,today());
        users.audit(auth,"CHECK_IN","appointment",appointmentId);events.publishEvent(new AppointmentEvent(appointmentId,"CHECKED_IN"));return token;
    }
    @Transactional public Long callNext(Authentication auth,long doctorId,long branchId,LocalDate date) {
        reception(auth);LocalDate selected=date==null?today():date;currentDay(selected);repo.lockDoctor(doctorId);
        if(repo.activeCalled(doctorId,selected)>0)throw conflict("Finish the current token before calling another patient");
        Long token=repo.nextWaiting(doctorId,branchId,selected);if(token==null)return null;
        Map<String,Object> row=lockToken(token);
        if(repo.transition(token,"WAITING","CALLED")!=1)throw conflict("Token was already called");
        long appointment=number(row,"appointment_id");repo.appointmentStatus(appointment,"CALLED");
        users.audit(auth,"CALL_TOKEN","queue_token",token);events.publishEvent(new AppointmentEvent(appointment,"CALLED"));return token;
    }
    private Map<String,Object> lockToken(long id) {
        Map<String,Object> initial=repo.token(id);repo.lockDoctor(number(initial,"doctor_id"));
        // Mutations lock doctor, appointment, then token in the same order.
        repo.lockedAppointment(number(initial,"appointment_id"));
        Map<String,Object> row=repo.lockedToken(id);currentDay(day(row.get("queue_date")));return row;
    }
    @Transactional public void finish(Authentication auth,long tokenId,boolean skip) {
        String role=users.role(auth);Map<String,Object> initial=repo.token(tokenId);
        if(!List.of("RECEPTION","ADMIN").contains(role)) {
            Long own="DOCTOR".equals(role)?repo.doctorForUser(users.id(auth)):null;
            if(own==null||own.longValue()!=number(initial,"doctor_id"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This token belongs to another doctor");
        }
        Map<String,Object> row=lockToken(tokenId);
        if(repo.transition(tokenId,"CALLED",skip?"SKIPPED":"COMPLETED")!=1)throw conflict("Only called tokens can be finished");
        long appointment=number(row,"appointment_id");repo.appointmentStatus(appointment,skip?"NO_SHOW":"COMPLETED");
        users.audit(auth,skip?"NO_SHOW_TOKEN":"COMPLETE_TOKEN","queue_token",tokenId);
        if(skip)events.publishEvent(new AppointmentEvent(appointment,"NO_SHOW"));
    }
    @Transactional public void cancel(Authentication auth,long tokenId) {
        reception(auth);Map<String,Object> row=lockToken(tokenId);
        if(!"WAITING".equals(row.get("status")))throw conflict("Only waiting patients can be deleted");
        long id=number(row,"appointment_id");Map<String,Object> appointment=repo.lockedAppointment(id);
        if(!"CHECKED_IN".equals(appointment.get("status")))throw conflict("Appointment changed; refresh the queue");
        if(repo.transition(tokenId,"WAITING","CANCELLED")!=1)throw conflict("Queue changed; refresh the queue");
        appointments.cancel(id);appointments.releaseSlot(number(appointment,"slot_id"));
        users.audit(auth,"CANCEL_QUEUE_VISIT","appointment",id);events.publishEvent(new AppointmentEvent(id,"CANCELLED"));
    }
    @Transactional public void reschedule(Authentication auth,long tokenId,long slotId) {
        reception(auth);Map<String,Object> row=lockToken(tokenId);
        if(!"WAITING".equals(row.get("status")))throw conflict("Only waiting patients can be rescheduled");
        long id=number(row,"appointment_id");Map<String,Object> appointment=repo.lockedAppointment(id);
        if(!"CHECKED_IN".equals(appointment.get("status")))throw conflict("Appointment changed; refresh the queue");
        if(appointments.slotDoctor(slotId)!=number(row,"doctor_id")||appointments.slotBranch(slotId)!=number(row,"branch_id"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose an available slot with the same doctor and branch");
        if(appointments.claimSlot(slotId)!=1)throw conflict("That slot is no longer available");
        appointments.changeSlot(id,slotId);appointments.releaseSlot(number(appointment,"slot_id"));
        repo.appointmentStatus(id,"CONFIRMED");
        if(repo.transition(tokenId,"WAITING","RESCHEDULED")!=1)throw conflict("Queue changed; refresh the queue");
        users.audit(auth,"RESCHEDULE_QUEUE_VISIT","appointment",id);events.publishEvent(new AppointmentEvent(id,"RESCHEDULED"));
    }
    public List<Map<String,Object>> list(Authentication auth,LocalDate date,Long branchId,Long doctorId) {
        LocalDate selected=date==null?today():date;String role=users.role(auth);
        if(List.of("RECEPTION","ADMIN","MANAGER").contains(role))return repo.list(selected,branchId,doctorId);
        if("DOCTOR".equals(role)) { Long own=repo.doctorForUser(users.id(auth));if(own!=null&&(doctorId==null||own.equals(doctorId)))return repo.list(selected,branchId,own); }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Queue access required");
    }
    public List<Map<String,Object>> findAppointments(Authentication auth,String search) {
        reception(auth);String term=search==null?"":search.trim();
        if(term.isEmpty()||term.length()>80)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a patient or appointment identifier (up to 80 characters)");
        return repo.findAppointments(term,today());
    }
    private void reception(Authentication auth) { if(!List.of("RECEPTION","ADMIN").contains(users.role(auth)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Reception access required"); }
}
