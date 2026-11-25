package lk.sunshine.smartcare.features.notification;

import lk.sunshine.smartcare.shared.CurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Service
public class ReviewService {
    private final ReviewRepository repo;
    private final CurrentUser users;
    public ReviewService(ReviewRepository repo,CurrentUser users) { this.repo=repo;this.users=users; }

    public List<Map<String,Object>> own(Authentication auth) { patient(auth);return repo.own(users.id(auth)); }
    public List<Map<String,Object>> allForAdmin(Authentication auth) { admin(auth);return repo.allForAdmin(); }

    @Transactional public long create(Authentication auth,long appointmentId,int rating,String comment) {
        patient(auth);Map<String,Object> appointment;
        try { appointment=repo.appointment(appointmentId); }
        catch(EmptyResultDataAccessException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Appointment not found"); }
        if(((Number)appointment.get("user_id")).longValue()!=users.id(auth))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This appointment belongs to another patient");
        if(!"COMPLETED".equals(appointment.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only completed appointments can receive feedback");
        String clean=clean(comment);List<Map<String,Object>> existing=repo.byAppointment(appointmentId);
        if(!existing.isEmpty()) {
            Map<String,Object> review=existing.get(0);long id=((Number)review.get("id")).longValue();
            if(review.get("deleted_at")==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"This appointment already has feedback");
            repo.restore(id,rating,clean);users.audit(auth,"CREATE_FEEDBACK","appointment_review",id);return id;
        }
        try { long id=repo.create(appointmentId,((Number)appointment.get("patient_id")).longValue(),rating,clean);users.audit(auth,"CREATE_FEEDBACK","appointment_review",id);return id; }
        catch(DataIntegrityViolationException e) { throw new ResponseStatusException(HttpStatus.CONFLICT,"This appointment already has feedback"); }
    }

    @Transactional public void update(Authentication auth,long id,int rating,String comment) {
        patient(auth);if(repo.updateOwned(id,users.id(auth),rating,clean(comment))!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Feedback not found");
        users.audit(auth,"UPDATE_FEEDBACK","appointment_review",id);
    }

    @Transactional public void delete(Authentication auth,long id) {
        String role=users.role(auth);long userId=users.id(auth);int changed;
        if("ADMIN".equals(role))changed=repo.softDeleteAny(id,userId);
        else if("PATIENT".equals(role))changed=repo.softDeleteOwned(id,userId,userId);
        else throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Patient or administrator required");
        if(changed!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Feedback not found");
        users.audit(auth,"DELETE_FEEDBACK","appointment_review",id);
    }

    private String clean(String comment) { return comment==null||comment.trim().isEmpty()?null:comment.trim(); }
    private void patient(Authentication auth) { if(!"PATIENT".equals(users.role(auth)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Patient account required"); }
    private void admin(Authentication auth) { if(!"ADMIN".equals(users.role(auth)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Administrator required"); }
}
