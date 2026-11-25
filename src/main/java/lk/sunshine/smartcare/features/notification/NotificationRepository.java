package lk.sunshine.smartcare.features.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class NotificationRepository {
    private final JdbcTemplate db;
    public NotificationRepository(JdbcTemplate db) { this.db=db; }
    public Map<String,Object> appointment(long id) { return db.queryForMap("SELECT a.id,a.status,p.user_id,s.starts_at,d.user_id AS doctor_user_id,d.display_name AS doctor_name,u.full_name AS patient_name,b.name AS branch_name FROM appointment a JOIN patient p ON p.id=a.patient_id JOIN app_user u ON u.id=p.user_id JOIN slot s ON s.id=a.slot_id JOIN doctor d ON d.id=s.doctor_id JOIN branch b ON b.id=a.branch_id WHERE a.id=?",id); }
    public Long doctorUser(long doctorId) { return db.query("SELECT user_id FROM doctor WHERE id=?",(rs,row)->(Long)rs.getObject(1),doctorId).stream().findFirst().orElse(null); }
    public void add(long userId,Long appointmentId,String type,String message,LocalDateTime dueAt) { db.update("INSERT INTO notification(user_id,appointment_id,type,message,due_at) VALUES(?,?,?,?,?)",userId,appointmentId,type,message,dueAt); }
    public void removePendingReminder(long appointmentId) { db.update("DELETE FROM notification WHERE appointment_id=? AND type='REMINDER'",appointmentId); }
    public int expirePendingReminders() { return db.update("DELETE n FROM notification n JOIN appointment a ON a.id=n.appointment_id JOIN slot s ON s.id=a.slot_id WHERE n.type='REMINDER' AND n.due_at IS NOT NULL AND (a.status<>'CONFIRMED' OR s.starts_at<=SYSDATETIME())"); }
    public int releaseReminders() { return db.update("UPDATE n SET due_at=NULL,created_at=SYSUTCDATETIME() FROM notification n JOIN appointment a ON a.id=n.appointment_id JOIN slot s ON s.id=a.slot_id WHERE n.type='REMINDER' AND n.due_at<=SYSDATETIME() AND a.status='CONFIRMED' AND s.starts_at>SYSDATETIME()"); }
    public List<Map<String,Object>> inbox(long userId) { return db.queryForList("SELECT id,appointment_id,type,message,CONVERT(varchar(33),created_at,126)+'Z' AS created_at,read_at FROM notification WHERE user_id=? AND due_at IS NULL ORDER BY created_at DESC,id DESC",userId); }
    public List<Map<String,Object>> upcoming(long userId) { return db.queryForList("SELECT appointment_id,message,CONVERT(varchar(19),due_at,126) AS due_at FROM (SELECT n.appointment_id,n.message,n.due_at,ROW_NUMBER() OVER(PARTITION BY n.appointment_id ORDER BY n.due_at) AS reminder_number FROM notification n JOIN appointment a ON a.id=n.appointment_id WHERE n.user_id=? AND n.type='REMINDER' AND n.due_at>SYSDATETIME() AND a.status='CONFIRMED') r WHERE reminder_number=1 ORDER BY due_at",userId); }
    public List<Map<String,Object>> pendingTextAlerts() { return db.queryForList("SELECT TOP (100) n.id,n.appointment_id,n.type,n.message,n.created_at,p.phone,p.contact_preference,a.status AS appointment_status FROM notification n JOIN patient p ON p.user_id=n.user_id JOIN appointment a ON a.id=n.appointment_id AND a.patient_id=p.id WHERE n.text_handled_at IS NULL AND n.due_at IS NULL AND n.type IN ('BOOKED','CONFIRMED','RESCHEDULED','CANCELLED','REMINDER') ORDER BY n.id"); }
    public void markTextHandled(long id) { db.update("UPDATE notification SET text_handled_at=SYSUTCDATETIME() WHERE id=? AND text_handled_at IS NULL",id); }
    public int markRead(long userId,long id) { return db.update("UPDATE notification SET read_at=SYSUTCDATETIME() WHERE id=? AND user_id=?",id,userId); }
}
