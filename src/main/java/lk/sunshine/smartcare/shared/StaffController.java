package lk.sunshine.smartcare.shared;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
public class StaffController {
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final CurrentUser users
    ;

    public StaffController(JdbcTemplate db, PasswordEncoder encoder, CurrentUser users) {
        this.db = db;
        this.encoder = encoder;
        this.users = users;
    }

    public record StaffInput(@Email @NotBlank String email, @NotBlank String fullName,
                             @NotBlank @Size(min = 8) String password,
                             @NotBlank @Pattern(regexp = "RECEPTION|MANAGER|ADMIN") String role) {}
    public record StaffUpdate(@Email @NotBlank String email, @NotBlank String fullName,
                              @NotNull Boolean active, @Size(min = 8) String password) {}

    @GetMapping
    public List<Map<String,Object>> all(Authentication auth) {
        admin(auth);
        return db.queryForList("SELECT u.id,u.email,u.full_name,u.role,u.active FROM app_user u WHERE u.role IN ('ADMIN','MANAGER','RECEPTION') ORDER BY u.full_name,u.id");
    }

    @PostMapping
    @Transactional
    public Map<String,Object> create(Authentication auth, @Valid @RequestBody StaffInput input) {
        admin(auth);
        String email = EmailPolicy.newAddress(input.email());
        if (db.queryForObject("SELECT COUNT(*) FROM app_user WHERE email=?", Integer.class, email) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already has an account");
        long id = db.queryForObject(
                "INSERT INTO app_user(email,password_hash,role,full_name) OUTPUT INSERTED.id VALUES(?,?,?,?)",
                Long.class, email, encoder.encode(input.password()), input.role(), input.fullName().trim());
        users.audit(auth, "CREATE_STAFF", "app_user", id);
        return Map.of("id", id);
    }

    @PutMapping("/{id}")
    @Transactional
    public Map<String,Object> update(Authentication auth,@PathVariable long id,@Valid @RequestBody StaffUpdate input) {
        admin(auth);
        long actorId=users.id(auth);
        Map<String,Object> target=target(id);
        String email=EmailPolicy.changedAddress(input.email(),String.valueOf(target.get("email")));
        if(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE email=? AND id<>?",Integer.class,email,id)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Email is already in use");
        boolean wasActive=Boolean.TRUE.equals(target.get("active"));
        if(!input.active()&&id==actorId)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"You cannot deactivate your own administrator account");
        if(wasActive&&!input.active()&&"ADMIN".equals(target.get("role")))requireAnotherAdministrator(id);
        boolean emailChanged=id==actorId&&!auth.getName().equalsIgnoreCase(email);
        db.update("UPDATE app_user SET email=?,full_name=?,active=? WHERE id=?",email,input.fullName().trim(),input.active(),id);
        audit(actorId,"UPDATE_STAFF",id);
        if(input.password()!=null&&!input.password().isBlank()) {
            db.update("UPDATE app_user SET password_hash=? WHERE id=?",encoder.encode(input.password()),id);
            audit(actorId,"RESET_STAFF_PASSWORD",id);
        }
        if(wasActive!=input.active())audit(actorId,input.active()?"REACTIVATE_STAFF":"DEACTIVATE_STAFF",id);
        return Map.of("message","Staff account saved","emailChanged",emailChanged);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String,String> deactivate(Authentication auth,@PathVariable long id) {
        admin(auth);
        long actorId=users.id(auth);
        Map<String,Object> target=target(id);
        if(id==actorId)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"You cannot deactivate your own administrator account");
        if(Boolean.TRUE.equals(target.get("active"))&&"ADMIN".equals(target.get("role")))requireAnotherAdministrator(id);
        if(db.update("UPDATE app_user SET active=0 WHERE id=? AND active=1",id)>0)audit(actorId,"DEACTIVATE_STAFF",id);
        return Map.of("message","Staff account deactivated");
    }

    @DeleteMapping("/{id}/permanent")
    @Transactional
    public Map<String,String> permanentlyDelete(Authentication auth,@PathVariable long id) {
        admin(auth);
        long actorId=users.id(auth);
        Map<String,Object> target=lockedTarget(id);
        if(id==actorId)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"You cannot permanently delete your own administrator account");
        if(Boolean.TRUE.equals(target.get("active")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Deactivate the staff account before permanently deleting it");
        String dependency=staffDependency(id);
        if(dependency!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,dependency);
        try {
            if(db.update("DELETE FROM app_user WHERE id=? AND active=0 AND role IN ('ADMIN','MANAGER','RECEPTION')",id)!=1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"The staff account changed; reload and try again");
        } catch(DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This staff account gained protected history and cannot be permanently deleted",e);
        }
        audit(actorId,"PERMANENT_DELETE_STAFF",id);
        return Map.of("message","Staff account permanently deleted");
    }

    private Map<String,Object> target(long id) {
        List<Map<String,Object>> found=db.queryForList("SELECT id,email,full_name,role,active FROM app_user WHERE id=? AND role IN ('ADMIN','MANAGER','RECEPTION')",id);
        if(found.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Staff account not found");
        return found.get(0);
    }

    private Map<String,Object> lockedTarget(long id) {
        List<Map<String,Object>> found=db.queryForList("SELECT id,email,full_name,role,active FROM app_user WITH (UPDLOCK,HOLDLOCK) WHERE id=? AND role IN ('ADMIN','MANAGER','RECEPTION')",id);
        if(found.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Staff account not found");
        return found.get(0);
    }

    private String staffDependency(long id) {
        if(countLocked("notification","user_id",id)>0)return "Staff account has notification history and cannot be permanently deleted";
        if(countLocked("audit_event","actor_user_id",id)>0)return "Staff account has audit history and cannot be permanently deleted";
        if(countLocked("appointment_review","deleted_by_user_id",id)>0)return "Staff account has feedback moderation history and cannot be permanently deleted";
        return null;
    }

    private int countLocked(String table,String column,long id) {
        return db.queryForObject("SELECT COUNT(*) FROM "+table+" WITH (UPDLOCK,HOLDLOCK) WHERE "+column+"=?",Integer.class,id);
    }

    private void requireAnotherAdministrator(long id) {
        int others=db.queryForObject("SELECT COUNT(*) FROM app_user WITH (UPDLOCK,HOLDLOCK) WHERE role='ADMIN' AND active=1 AND id<>?",Integer.class,id);
        if(others==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"The final active administrator cannot be deactivated");
    }

    private void audit(long actorId,String action,long entityId) {
        db.update("INSERT INTO audit_event(actor_user_id,action,entity_name,entity_id) VALUES(?,?,'app_user',?)",actorId,action,entityId);
    }

    private void admin(Authentication auth) {
        if (!"ADMIN".equals(users.role(auth)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator required");
    }
}
