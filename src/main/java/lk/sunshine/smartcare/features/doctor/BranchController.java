package lk.sunshine.smartcare.features.doctor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lk.sunshine.smartcare.shared.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/branches")
public class BranchController {
    private final JdbcTemplate db;
    private final CurrentUser users;
    public BranchController(JdbcTemplate db,CurrentUser users) { this.db=db;this.users=users; }
    public record BranchInput(@NotBlank @Size(max=120) String name,@Size(max=300) String address) {}
    @GetMapping public List<Map<String,Object>> all() { return db.queryForList("SELECT id,name,address FROM branch WHERE active=1 ORDER BY name"); }
    @PostMapping @Transactional public Map<String,Object> add(Authentication auth,@Valid @RequestBody BranchInput input) {
        if(!"ADMIN".equals(users.role(auth)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Administrator required");
        String name=input.name().trim();
        if(db.queryForObject("SELECT COUNT(*) FROM branch WHERE LOWER(name)=LOWER(?)",Integer.class,name)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Branch already exists");
        long id=db.queryForObject("INSERT INTO branch(name,address) OUTPUT INSERTED.id VALUES(?,?)",Long.class,name,input.address());
        users.audit(auth,"CREATE_BRANCH","branch",id);
        return Map.of("id",id);
    }
    @DeleteMapping("/{id}") @Transactional public Map<String,String> remove(Authentication auth,@PathVariable long id) {
        if(!"ADMIN".equals(users.role(auth)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Administrator required");
        if(db.queryForObject("SELECT COUNT(*) FROM branch WHERE id=?",Integer.class,id)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Branch not found");
        int removed=db.update("DELETE FROM branch WHERE id=? AND NOT EXISTS(SELECT 1 FROM doctor_branch WHERE branch_id=?) AND NOT EXISTS(SELECT 1 FROM slot WHERE branch_id=?) AND NOT EXISTS(SELECT 1 FROM schedule_template WHERE branch_id=?) AND NOT EXISTS(SELECT 1 FROM appointment WHERE branch_id=?) AND NOT EXISTS(SELECT 1 FROM report_template WHERE branch_id=?)",id,id,id,id,id,id);
        if(removed==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Branch has doctors or appointments");
        users.audit(auth,"DELETE_BRANCH","branch",id);
        return Map.of("message","Branch removed");
    }
}
