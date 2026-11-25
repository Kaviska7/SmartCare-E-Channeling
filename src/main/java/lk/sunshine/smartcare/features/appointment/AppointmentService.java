package lk.sunshine.smartcare.features.appointment;

import lk.sunshine.smartcare.features.notification.AppointmentEvent;
import lk.sunshine.smartcare.shared.CurrentUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

@Service
public class AppointmentService {
    private final AppointmentRepository repo; private final CurrentUser users; private final ApplicationEventPublisher events;
    public AppointmentService(AppointmentRepository repo,CurrentUser users,ApplicationEventPublisher events) { this.repo=repo;this.users=users;this.events=events; }
    @Transactional public long book(Authentication auth,long slotId) { return book(auth,slotId,null,null); }
    @Transactional public long book(Authentication auth,long slotId,Long branchId) { return book(auth,slotId,branchId,null); }
    @Transactional public long book(Authentication auth,long slotId,Long branchId,String paymentMethod) {
        patientOnly(auth);
        if(paymentMethod!=null && !List.of("DEMO_CARD","PAY_AT_HOSPITAL").contains(paymentMethod))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a payment method");
        if(branchId!=null && repo.slotBranch(slotId)!=branchId)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a doctor at the selected branch");
        if(repo.claimSlot(slotId)!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"That slot is no longer available");
        long id=repo.create(repo.patientId(users.id(auth)),slotId);
        if(paymentMethod!=null) {
            double fee=((Number)repo.one(id).get("fee")).doubleValue();
            repo.payment(id,fee,paymentMethod,"DEMO_CARD".equals(paymentMethod)?"PENDING_DEMO":"PAY_AT_HOSPITAL");
        }
        users.audit(auth,"BOOK_APPOINTMENT","appointment",id);events.publishEvent(new AppointmentEvent(id,"BOOKED"));return id;
    }
    public List<Map<String,Object>> searchPatients(Authentication auth,String search) {
        reception(auth);
        String term=search==null?"":search.trim();
        if(term.length()<2)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter at least two characters");
        return repo.searchPatients(term);
    }
    @Transactional public long bookForPatient(Authentication auth,long patientId,long slotId) {
        reception(auth);
        if(repo.patientExists(patientId)!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Patient not found");
        if(repo.claimSlot(slotId)!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"That slot is no longer available");
        long id=repo.create(patientId,slotId);
        users.audit(auth,"STAFF_BOOK_APPOINTMENT","appointment",id);
        events.publishEvent(new AppointmentEvent(id,"BOOKED"));
        return id;
    }
    @Transactional public void reschedule(Authentication auth,long id,long newSlotId) {
        Map<String,Object> a=manageable(auth,id);changeable(a);
        if(((Number)a.get("doctor_id")).longValue()!=repo.slotDoctor(newSlotId))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a slot with the same doctor");
        if(a.get("branch_id")!=null && ((Number)a.get("branch_id")).longValue()!=repo.slotBranch(newSlotId))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a slot at the same branch");
        if(repo.claimSlot(newSlotId)!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"That slot is no longer available");
        repo.changeSlot(id,newSlotId);repo.releaseSlot(((Number)a.get("slot_id")).longValue());users.audit(auth,"RESCHEDULE_APPOINTMENT","appointment",id);events.publishEvent(new AppointmentEvent(id,"RESCHEDULED"));
    }
    @Transactional public void cancel(Authentication auth,long id) { Map<String,Object> a=manageable(auth,id);changeable(a);repo.cancel(id);repo.releaseSlot(((Number)a.get("slot_id")).longValue());users.audit(auth,"CANCEL_APPOINTMENT","appointment",id);events.publishEvent(new AppointmentEvent(id,"CANCELLED")); }
    @Transactional public void confirm(Authentication auth,long id) {
        if(!"ADMIN".equals(users.role(auth)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Administrator required");
        if(repo.confirm(id)!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Only a future pending appointment with an active doctor and branch can be confirmed");
        users.audit(auth,"CONFIRM_APPOINTMENT","appointment",id);events.publishEvent(new AppointmentEvent(id,"CONFIRMED"));
    }
    public List<Map<String,Object>> list(Authentication auth) { String role=users.role(auth);if("PATIENT".equals(role))return repo.listForPatient(users.id(auth));if("DOCTOR".equals(role))return repo.listForDoctor(users.id(auth));if("RECEPTION".equals(role))return repo.listForStaff();if("ADMIN".equals(role))return repo.listForAdmin();throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Access denied"); }
    @Transactional public Map<String,Object> payment(Authentication auth,long id,String choice) {
        Map<String,Object> a=owned(auth,id);changeable(a);
        if(!List.of("DEMO_PAID","PAY_AT_HOSPITAL").contains(choice))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid payment choice");
        if("DEMO_PAID".equals(repo.paymentStatus(id)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Demo payment is already recorded");
        repo.payment(id,((Number)a.get("fee")).doubleValue(),"DEMO_PAID".equals(choice)?"DEMO_CARD":"PAY_AT_HOSPITAL",choice);
        users.audit(auth,"DEMO_PAYMENT","appointment",id);return repo.receipt(id);
    }
    public Map<String,Object> receipt(Authentication auth,long id) { owned(auth,id);return repo.receipt(id); }
    private Map<String,Object> owned(Authentication auth,long id) { patientOnly(auth);Map<String,Object> a=repo.one(id);if(((Number)a.get("user_id")).longValue()!=users.id(auth))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This appointment belongs to another patient");return a; }
    private Map<String,Object> manageable(Authentication auth,long id) {
        String role=users.role(auth);
        if("ADMIN".equals(role))return repo.one(id);
        if(!List.of("PATIENT","DOCTOR").contains(role))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Patient, assigned doctor, or administrator required");
        Map<String,Object> a=repo.one(id);
        Object owner=a.get("PATIENT".equals(role)?"user_id":"doctor_user_id");
        if(!(owner instanceof Number ownerId) || ownerId.longValue()!=users.id(auth))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This appointment is not assigned to you");
        return a;
    }
    private void changeable(Map<String,Object> a) { if(!List.of("PENDING","CONFIRMED").contains(String.valueOf(a.get("status"))) || !((java.sql.Timestamp)a.get("starts_at")).toLocalDateTime().isAfter(LocalDateTime.now()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only upcoming pending or confirmed appointments can be changed"); }
    private void patientOnly(Authentication auth) { if(!"PATIENT".equals(users.role(auth)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Patient account required"); }
    private void reception(Authentication auth) { if(!List.of("RECEPTION","ADMIN").contains(users.role(auth)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Reception access required"); }
}
