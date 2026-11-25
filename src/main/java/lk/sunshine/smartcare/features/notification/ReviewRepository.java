package lk.sunshine.smartcare.features.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class ReviewRepository {
    private final JdbcTemplate db;
    public ReviewRepository(JdbcTemplate db) { this.db=db; }

    public Map<String,Object> appointment(long appointmentId) {
        return db.queryForMap("SELECT a.id,a.status,p.id AS patient_id,p.user_id FROM appointment a JOIN patient p ON p.id=a.patient_id WHERE a.id=?",appointmentId);
    }
    public List<Map<String,Object>> byAppointment(long appointmentId) {
        return db.queryForList("SELECT id,deleted_at FROM appointment_review WHERE appointment_id=?",appointmentId);
    }
    public long create(long appointmentId,long patientId,int rating,String comment) {
        return db.queryForObject("INSERT INTO appointment_review(appointment_id,patient_id,rating,comment) OUTPUT INSERTED.id VALUES(?,?,?,?)",Long.class,appointmentId,patientId,rating,comment);
    }
    public void restore(long id,int rating,String comment) {
        db.update("UPDATE appointment_review SET rating=?,comment=?,updated_at=SYSUTCDATETIME(),deleted_at=NULL,deleted_by_user_id=NULL WHERE id=?",rating,comment,id);
    }
    public int updateOwned(long id,long userId,int rating,String comment) {
        return db.update("UPDATE r SET rating=?,comment=?,updated_at=SYSUTCDATETIME() FROM appointment_review r JOIN patient p ON p.id=r.patient_id WHERE r.id=? AND p.user_id=? AND r.deleted_at IS NULL",rating,comment,id,userId);
    }
    public int softDeleteOwned(long id,long ownerUserId,long deletedByUserId) {
        return db.update("UPDATE r SET deleted_at=SYSUTCDATETIME(),deleted_by_user_id=?,updated_at=SYSUTCDATETIME() FROM appointment_review r JOIN patient p ON p.id=r.patient_id WHERE r.id=? AND p.user_id=? AND r.deleted_at IS NULL",deletedByUserId,id,ownerUserId);
    }
    public int softDeleteAny(long id,long deletedByUserId) {
        return db.update("UPDATE appointment_review SET deleted_at=SYSUTCDATETIME(),deleted_by_user_id=?,updated_at=SYSUTCDATETIME() WHERE id=? AND deleted_at IS NULL",deletedByUserId,id);
    }
    public List<Map<String,Object>> own(long userId) {
        return db.queryForList("SELECT r.id,r.appointment_id,r.rating,r.comment,r.created_at,r.updated_at,s.starts_at,d.display_name AS doctor_name,b.name AS branch_name FROM appointment_review r JOIN patient p ON p.id=r.patient_id JOIN appointment a ON a.id=r.appointment_id JOIN slot s ON s.id=a.slot_id JOIN doctor d ON d.id=s.doctor_id JOIN branch b ON b.id=a.branch_id WHERE p.user_id=? AND r.deleted_at IS NULL ORDER BY r.updated_at DESC",userId);
    }
    public List<Map<String,Object>> allForAdmin() {
        return db.queryForList("SELECT r.id,r.appointment_id,r.rating,r.comment,r.created_at,r.updated_at,pu.full_name AS patient_name,pu.email AS patient_email,d.display_name AS doctor_name,b.name AS branch_name,s.starts_at FROM appointment_review r JOIN patient p ON p.id=r.patient_id JOIN app_user pu ON pu.id=p.user_id JOIN appointment a ON a.id=r.appointment_id JOIN slot s ON s.id=a.slot_id JOIN doctor d ON d.id=s.doctor_id JOIN branch b ON b.id=a.branch_id WHERE r.deleted_at IS NULL ORDER BY r.updated_at DESC,r.id DESC");
    }
}
