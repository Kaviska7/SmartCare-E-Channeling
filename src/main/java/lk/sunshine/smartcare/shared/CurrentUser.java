package lk.sunshine.smartcare.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CurrentUser {
    private final JdbcTemplate db;
    public CurrentUser(JdbcTemplate db) { this.db = db; }
    public long id(Authentication auth) { return activeValue(auth,"id",Long.class); }
    public String role(Authentication auth) { return activeValue(auth,"role",String.class); }
    private <T> T activeValue(Authentication auth,String column,Class<T> type) {
        if(auth==null || !auth.isAuthenticated())throw signedOut();
        try {
            return db.queryForObject("SELECT "+column+" FROM app_user WHERE email=? AND active=1",type,auth.getName());
        } catch(EmptyResultDataAccessException e) {
            throw signedOut();
        }
    }
    private ResponseStatusException signedOut() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Your account is unavailable. Please sign in again."); }
    public void audit(Authentication auth, String action, String entity, Long entityId) {
        db.update("INSERT INTO audit_event(actor_user_id,action,entity_name,entity_id) VALUES(?,?,?,?)", auth == null ? null : id(auth), action, entity, entityId);
    }
}
