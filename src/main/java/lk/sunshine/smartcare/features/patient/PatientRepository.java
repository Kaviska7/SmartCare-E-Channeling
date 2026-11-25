package lk.sunshine.smartcare.features.patient;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class PatientRepository {
    private final JdbcTemplate db;
    public PatientRepository(JdbcTemplate db) { this.db=db; }
    public long createUser(String email,String hash,String name) { return db.queryForObject("INSERT INTO app_user(email,password_hash,role,full_name) OUTPUT INSERTED.id VALUES(?,?,'PATIENT',?)",Long.class,email,hash,name); }
    public int emailCount(String email) { return db.queryForObject("SELECT COUNT(*) FROM app_user WHERE email=?",Integer.class,email); }
    public int nicCount(String nic) { return db.queryForObject("SELECT COUNT(*) FROM patient WHERE nic=?",Integer.class,nic); }
    public int emailCountExcept(String email,long userId) { return db.queryForObject("SELECT COUNT(*) FROM app_user WHERE email=? AND id<>?",Integer.class,email,userId); }
    public int nicCountExcept(String nic,long userId) { return db.queryForObject("SELECT COUNT(*) FROM patient WHERE nic=? AND user_id<>?",Integer.class,nic,userId); }
    public List<Map<String,Object>> allForAdmin() { return db.queryForList("SELECT u.id,u.full_name,u.email,p.nic,p.date_of_birth,p.phone,p.address,u.active FROM patient p JOIN app_user u ON u.id=p.user_id ORDER BY u.full_name,u.id"); }
    public Map<String,Object> adminState(long userId) { List<Map<String,Object>> found=db.queryForList("SELECT u.id,u.email,u.active FROM patient p JOIN app_user u ON u.id=p.user_id WHERE u.id=?",userId);return found.isEmpty()?null:found.get(0); }
    public Map<String,Object> lockedAdminState(long userId) { List<Map<String,Object>> found=db.queryForList("SELECT u.id,u.active,p.id AS patient_id FROM app_user u WITH (UPDLOCK,HOLDLOCK) JOIN patient p WITH (UPDLOCK,HOLDLOCK) ON p.user_id=u.id WHERE u.id=? AND u.role='PATIENT'",userId);return found.isEmpty()?null:found.get(0); }
    public void createPatient(long userId,String nic,String dob,String phone,String address,String preference) { db.update("INSERT INTO patient(user_id,nic,date_of_birth,phone,address,contact_preference) VALUES(?,?,?,?,?,?)",userId,nic,dob,phone,address,preference); }
    public Map<String,Object> profile(long userId) { return db.queryForMap("SELECT p.id,u.full_name,u.email,p.nic,p.date_of_birth,p.phone,p.address,p.contact_preference FROM patient p JOIN app_user u ON u.id=p.user_id WHERE p.user_id=?",userId); }
    public void update(long userId,String name,String email,String nic,String phone,String address,String preference) { db.update("UPDATE app_user SET full_name=?,email=? WHERE id=?",name,email,userId); db.update("UPDATE patient SET nic=?,phone=?,address=?,contact_preference=? WHERE user_id=?",nic,phone,address,preference,userId); }
    public void updateByAdmin(long userId,String name,String email,String nic,String dateOfBirth,String phone,String address,boolean active) { db.update("UPDATE app_user SET full_name=?,email=?,active=? WHERE id=?",name,email,active,userId);db.update("UPDATE patient SET nic=?,date_of_birth=?,phone=?,address=? WHERE user_id=?",nic,dateOfBirth,phone,address,userId); }
    public void updatePassword(long userId,String hash) { db.update("UPDATE app_user SET password_hash=? WHERE id=?",hash,userId); }
    public int deactivate(long userId) { return db.update("UPDATE app_user SET active=0 WHERE id=? AND role='PATIENT' AND active=1",userId); }
    public int appointmentCountLocked(long patientId) { return db.queryForObject("SELECT COUNT(*) FROM appointment WITH (UPDLOCK,HOLDLOCK) WHERE patient_id=?",Integer.class,patientId); }
    public int permanentlyDelete(long userId,long patientId) {
        db.update("UPDATE audit_event SET actor_user_id=NULL WHERE actor_user_id=?",userId);
        db.update("UPDATE appointment_review SET deleted_by_user_id=NULL WHERE deleted_by_user_id=?",userId);
        db.update("DELETE FROM appointment_review WHERE patient_id=?",patientId);
        db.update("DELETE FROM notification WHERE user_id=?",userId);
        db.update("DELETE FROM password_reset_token WHERE user_id=?",userId);
        db.update("DELETE FROM emergency_contact WHERE patient_id=?",patientId);
        db.update("DELETE FROM patient WHERE id=? AND user_id=?",patientId,userId);
        return db.update("DELETE FROM app_user WHERE id=? AND role='PATIENT' AND active=0",userId);
    }
    public List<Map<String,Object>> contacts(long userId) { return db.queryForList("SELECT e.id,e.full_name,e.relationship,e.phone FROM emergency_contact e JOIN patient p ON p.id=e.patient_id WHERE p.user_id=? ORDER BY e.id",userId); }
    public void addContact(long userId,String name,String relationship,String phone) { db.update("INSERT INTO emergency_contact(patient_id,full_name,relationship,phone) SELECT id,?,?,? FROM patient WHERE user_id=?",name,relationship,phone,userId); }
    public int deleteContact(long userId,long contactId) { return db.update("DELETE e FROM emergency_contact e JOIN patient p ON p.id=e.patient_id WHERE e.id=? AND p.user_id=?",contactId,userId); }
}
