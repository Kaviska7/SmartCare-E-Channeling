package lk.sunshine.smartcare.features.notification;

import lk.sunshine.smartcare.shared.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class NotificationService {
    private static final Logger log=LoggerFactory.getLogger(NotificationService.class);
    private static final DateTimeFormatter VISIT_TIME=DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a");
    private final NotificationRepository repo; private final CurrentUser users;
    public NotificationService(NotificationRepository repo,CurrentUser users) { this.repo=repo;this.users=users; }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void onAppointment(AppointmentEvent event) {
        try { saveAppointmentAlerts(event); }
        catch(RuntimeException e) { log.error("Appointment {} committed, but its in-app alerts could not be saved: {}",event.appointmentId(),e.toString()); }
    }
    private void saveAppointmentAlerts(AppointmentEvent event) {
        Map<String,Object> a=repo.appointment(event.appointmentId());long patient=((Number)a.get("user_id")).longValue();
        LocalDateTime start=start(a.get("starts_at"));
        String doctorName=String.valueOf(a.get("doctor_name"));
        if(!doctorName.toLowerCase().startsWith("dr."))doctorName="Dr. "+doctorName;
        String visit="appointment #"+event.appointmentId()+" with "+doctorName+" at "+a.get("branch_name")+" on "+start.format(VISIT_TIME);
        String message=switch(event.type()) { case "BOOKED" -> "Booking submitted for administrator confirmation: "+visit+".";case "CONFIRMED" -> "Booking confirmed: "+visit+".";case "RESCHEDULED" -> "Schedule changed: your "+visit+" has a new time and is "+String.valueOf(a.get("status")).toLowerCase()+".";case "CANCELLED" -> "Appointment cancelled: your "+visit+".";case "CHECKED_IN" -> "You have checked in for "+visit+". Your queue token is ready.";case "CALLED" -> "Your queue token has been called for "+visit+".";case "NO_SHOW" -> "Your "+visit+" was marked as no-show. Please contact reception if this is incorrect.";default -> "Your "+visit+" has been updated."; };
        repo.add(patient,event.appointmentId(),event.type(),message,null);
        Object doctor=a.get("doctor_user_id");
        if(doctor!=null && (event.type().equals("BOOKED")||event.type().equals("CONFIRMED")||event.type().equals("RESCHEDULED")||event.type().equals("CANCELLED"))) {
            String action=switch(event.type()) {case "BOOKED" -> "New booking request";case "CONFIRMED" -> "Booking confirmed";case "RESCHEDULED" -> "Appointment rescheduled";default -> "Appointment cancelled";};
            repo.add(((Number)doctor).longValue(),event.appointmentId(),event.type(),action+": "+a.get("patient_name")+", #"+event.appointmentId()+", "+start.format(VISIT_TIME)+" at "+a.get("branch_name")+".",null);
        }
        if(event.type().equals("CONFIRMED")||event.type().equals("RESCHEDULED")) {
            repo.removePendingReminder(event.appointmentId());
            LocalDateTime now=LocalDateTime.now();
            if("CONFIRMED".equals(a.get("status"))&&start.isAfter(now)) {
                LocalDateTime oneDay=start.minusDays(1),twoHours=start.minusHours(2);
                repo.add(patient,event.appointmentId(),"REMINDER","Appointment reminder: "+visit+".",oneDay.isAfter(now)?oneDay:null);
                if(twoHours.isAfter(now))repo.add(patient,event.appointmentId(),"REMINDER","Your appointment starts in about two hours: "+visit+".",twoHours);
            }
        } else if(event.type().equals("CANCELLED"))repo.removePendingReminder(event.appointmentId());
        if(event.type().equals("CALLED") && doctor!=null)repo.add(((Number)doctor).longValue(),event.appointmentId(),"NEXT_PATIENT","Your next patient, "+a.get("patient_name")+", is ready.",null);
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void onSchedule(ScheduleEvent event) {
        try { Long doctor=repo.doctorUser(event.doctorId());if(doctor!=null)repo.add(doctor,null,"SCHEDULE_CHANGED",event.message(),null); }
        catch(RuntimeException e) { log.error("Doctor {} schedule changed, but its in-app alert could not be saved: {}",event.doctorId(),e.toString()); }
    }
    private LocalDateTime start(Object value) { return value instanceof java.sql.Timestamp time?time.toLocalDateTime():(LocalDateTime)value; }
    @Scheduled(fixedDelay=60000) public void releaseDueReminders() { repo.expirePendingReminders();repo.releaseReminders(); }
    public List<Map<String,Object>> inbox(Authentication auth) { return repo.inbox(users.id(auth)); }
    public List<Map<String,Object>> upcoming(Authentication auth) { return repo.upcoming(users.id(auth)); }
    public void read(Authentication auth,long id) { if(repo.markRead(users.id(auth),id)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification not found"); }
}
