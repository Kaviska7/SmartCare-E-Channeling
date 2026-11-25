package lk.sunshine.smartcare.features.patient;

import lk.sunshine.smartcare.shared.CurrentUser;
import lk.sunshine.smartcare.shared.EmailPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;

@Service
public class PatientService {
    private final PatientRepository repo; private final PasswordEncoder encoder; private final CurrentUser users;
    public PatientService(PatientRepository repo,PasswordEncoder encoder,CurrentUser users) { this.repo=repo;this.encoder=encoder;this.users=users; }
    @Transactional public long register(PatientController.Registration r) { return register(r,true); }
    private long register(PatientController.Registration r,boolean allowText) {
        String email=EmailPolicy.newAddress(r.email()),nic=r.nic().trim();
        if(repo.emailCount(email)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Email already has an account");
        if(repo.nicCount(nic)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"NIC is already registered");
        long id=repo.createUser(email,encoder.encode(r.password()),r.fullName().trim());
        repo.createPatient(id,nic,r.dateOfBirth().toString(),r.phone().trim(),r.address(),allowText && "IN_APP_AND_TEXT".equals(r.contactPreference())?"IN_APP_AND_TEXT":"IN_APP");return id;
    }
    @Transactional public long registerByAdmin(Authentication auth,PatientController.Registration r) { requireAdmin(auth);long id=register(r,false);users.audit(auth,"CREATE_PATIENT","app_user",id);return id; }
    public List<Map<String,Object>> allForAdmin(Authentication auth) { requireAdmin(auth);return repo.allForAdmin(); }
    @Transactional public void updateByAdmin(Authentication auth,long userId,PatientController.AdminUpdate update) {
        requireAdmin(auth);
        Map<String,Object> state=repo.adminState(userId);
        if(state==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Patient account not found");
        String email=EmailPolicy.changedAddress(update.email(),String.valueOf(state.get("email"))),nic=update.nic().trim(),phone=update.phone().trim();
        if(repo.emailCountExcept(email,userId)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Email is already in use");
        if(repo.nicCountExcept(nic,userId)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"NIC is already registered");
        boolean wasActive=Boolean.TRUE.equals(state.get("active"));
        repo.updateByAdmin(userId,update.fullName().trim(),email,nic,update.dateOfBirth().toString(),phone,update.address(),update.active());
        users.audit(auth,"UPDATE_PATIENT","app_user",userId);
        if(update.password()!=null&&!update.password().isBlank()){repo.updatePassword(userId,encoder.encode(update.password()));users.audit(auth,"RESET_PATIENT_PASSWORD","app_user",userId);}
        if(wasActive!=update.active())users.audit(auth,update.active()?"REACTIVATE_PATIENT":"DEACTIVATE_PATIENT","app_user",userId);
    }
    @Transactional public void deactivateByAdmin(Authentication auth,long userId) {
        requireAdmin(auth);
        if(repo.adminState(userId)==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Patient account not found");
        if(repo.deactivate(userId)>0)users.audit(auth,"DEACTIVATE_PATIENT","app_user",userId);
    }
    @Transactional public void permanentlyDeleteByAdmin(Authentication auth,long userId) {
        requireAdmin(auth);
        Map<String,Object> state=repo.lockedAdminState(userId);
        if(state==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Patient account not found");
        if(Boolean.TRUE.equals(state.get("active")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Deactivate the patient account before permanently deleting it");
        long patientId=((Number)state.get("patient_id")).longValue();
        if(repo.appointmentCountLocked(patientId)>0)throw protectedHistory("appointment, payment, or queue history");
        try {
            if(repo.permanentlyDelete(userId,patientId)!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"The patient account changed; reload and try again");
        } catch(DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This patient account gained protected history and cannot be permanently deleted",e);
        }
        users.audit(auth,"PERMANENT_DELETE_PATIENT","app_user",userId);
    }
    private ResponseStatusException protectedHistory(String history) { return new ResponseStatusException(HttpStatus.CONFLICT,"Patient account has "+history+" and cannot be permanently deleted"); }
    public Map<String,Object> profile(Authentication auth) { requirePatient(auth);return repo.profile(users.id(auth)); }
    @Transactional public boolean update(Authentication auth,PatientController.Profile p) {
        requirePatient(auth);
        long userId=users.id(auth);
        String email=EmailPolicy.changedAddress(p.email(),auth.getName()),nic=p.nic().trim(),phone=p.phone().trim();
        if(repo.emailCountExcept(email,userId)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Email is already in use");
        if(repo.nicCountExcept(nic,userId)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"NIC is already registered");
        users.audit(auth,"UPDATE_PROFILE","patient",null);
        repo.update(userId,p.fullName().trim(),email,nic,phone,p.address(),p.contactPreference());
        return !auth.getName().equalsIgnoreCase(email);
    }
    public List<Map<String,Object>> contacts(Authentication auth) { requirePatient(auth);return repo.contacts(users.id(auth)); }
    @Transactional public void addContact(Authentication auth,PatientController.Contact c) { requirePatient(auth);repo.addContact(users.id(auth),c.fullName(),c.relationship(),c.phone());users.audit(auth,"ADD_CONTACT","emergency_contact",null); }
    @Transactional public void deleteContact(Authentication auth,long id) { requirePatient(auth);if(repo.deleteContact(users.id(auth),id)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Contact not found");users.audit(auth,"DELETE_CONTACT","emergency_contact",id); }
    private void requireAdmin(Authentication auth) { if(!"ADMIN".equals(users.role(auth)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Administrator required"); }
    private void requirePatient(Authentication auth) { if(!"PATIENT".equals(users.role(auth)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Patient account required"); }
}
