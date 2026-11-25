package lk.sunshine.smartcare.features.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class QueueRepository {
    private final JdbcTemplate db;
    public QueueRepository(JdbcTemplate db) { this.db=db; }
    public void lockDoctor(long id) {
        if(db.queryForList("SELECT id FROM doctor WITH (UPDLOCK,HOLDLOCK) WHERE id=?",Long.class,id).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Doctor not found");
    }
    public Map<String,Object> appointment(long id) {
        return db.queryForMap("SELECT a.id,a.slot_id,a.branch_id,a.status,s.doctor_id,CAST(s.starts_at AS DATE) appointment_date FROM appointment a JOIN slot s ON s.id=a.slot_id WHERE a.id=?",id);
    }
    public Map<String,Object> lockedAppointment(long id) {
        return db.queryForMap("SELECT a.id,a.slot_id,a.branch_id,a.status,s.doctor_id,CAST(s.starts_at AS DATE) appointment_date FROM appointment a WITH (UPDLOCK,HOLDLOCK) JOIN slot s ON s.id=a.slot_id WHERE a.id=?",id);
    }
    public Map<String,Object> token(long id) {
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM queue_token WHERE id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Queue token not found");
        return rows.get(0);
    }
    public Map<String,Object> lockedToken(long id) { return db.queryForMap("SELECT * FROM queue_token WITH (UPDLOCK,HOLDLOCK) WHERE id=?",id); }
    public List<Map<String,Object>> findAppointments(String search,LocalDate date) {
        String text="%"+search.toLowerCase(java.util.Locale.ROOT)+"%";
        return db.queryForList("""
            SELECT TOP 30 a.id appointment_id,p.id patient_id,u.full_name patient_name,p.nic,p.phone,
                a.status,s.starts_at,d.id doctor_id,d.display_name doctor_name,b.name branch_name,a.branch_id,
                CAST(1 AS BIT) can_check_in
            FROM appointment a JOIN patient p ON p.id=a.patient_id JOIN app_user u ON u.id=p.user_id
            JOIN slot s ON s.id=a.slot_id JOIN doctor d ON d.id=s.doctor_id JOIN branch b ON b.id=a.branch_id
            WHERE a.status='CONFIRMED' AND CAST(s.starts_at AS DATE)=?
                AND NOT EXISTS(SELECT 1 FROM queue_token q WHERE q.appointment_id=a.id AND q.status IN ('WAITING','CALLED'))
                AND (a.id=TRY_CONVERT(BIGINT,?) OR p.id=TRY_CONVERT(BIGINT,?) OR u.id=TRY_CONVERT(BIGINT,?)
                OR LOWER(u.full_name) LIKE ? OR LOWER(p.nic) LIKE ? OR p.phone LIKE ?)
            ORDER BY s.starts_at,a.id
            """,date,search,search,search,text,text,text);
    }
    public int markCheckedIn(long id) { return db.update("UPDATE appointment SET status='CHECKED_IN',updated_at=SYSUTCDATETIME() WHERE id=? AND status='CONFIRMED'",id); }
    public int nextNumber(long doctorId,LocalDate date) {
        return db.queryForObject("SELECT ISNULL(MAX(token_number),0)+1 FROM queue_token WITH (UPDLOCK,HOLDLOCK) WHERE doctor_id=? AND queue_date=?",Integer.class,doctorId,date);
    }
    public void add(long appointmentId,long doctorId,int number,LocalDate date) {
        db.update("INSERT INTO queue_token(appointment_id,doctor_id,queue_date,token_number,branch_id,appointment_starts_at) SELECT ?,?,?,?,a.branch_id,s.starts_at FROM appointment a JOIN slot s ON s.id=a.slot_id WHERE a.id=?",appointmentId,doctorId,date,number,appointmentId);
    }
    public List<Map<String,Object>> list(LocalDate date,Long branchId,Long doctorId) {
        return db.queryForList("""
            SELECT q.id,q.appointment_id,q.doctor_id,q.branch_id,q.queue_date,q.token_number,q.status,
                q.appointment_starts_at starts_at,q.checked_in_at,q.called_at,q.completed_at,
                CASE WHEN q.status='WAITING' THEN DATEDIFF(MINUTE,q.checked_in_at,SYSUTCDATETIME())
                     ELSE DATEDIFF(MINUTE,q.checked_in_at,COALESCE(q.called_at,q.completed_at,q.checked_in_at)) END wait_minutes,
                u.full_name patient_name,d.display_name doctor_name,b.name branch_name
            FROM queue_token q JOIN appointment a ON a.id=q.appointment_id
            JOIN patient p ON p.id=a.patient_id JOIN app_user u ON u.id=p.user_id
            JOIN doctor d ON d.id=q.doctor_id JOIN branch b ON b.id=q.branch_id
            WHERE q.queue_date=? AND (? IS NULL OR q.branch_id=?) AND (? IS NULL OR q.doctor_id=?)
            ORDER BY b.name,d.display_name,q.token_number
            """,date,branchId,branchId,doctorId,doctorId);
    }
    public Long doctorForUser(long userId) {
        List<Long> ids=db.queryForList("SELECT id FROM doctor WHERE user_id=?",Long.class,userId);return ids.isEmpty()?null:ids.get(0);
    }
    public Long nextWaiting(long doctorId,long branchId,LocalDate date) {
        List<Long> ids=db.queryForList("SELECT TOP 1 id FROM queue_token WHERE doctor_id=? AND branch_id=? AND queue_date=? AND status='WAITING' ORDER BY token_number",Long.class,doctorId,branchId,date);
        return ids.isEmpty()?null:ids.get(0);
    }
    public int activeCalled(long doctorId,LocalDate date) {
        return db.queryForObject("SELECT COUNT(*) FROM queue_token WITH (UPDLOCK,HOLDLOCK) WHERE doctor_id=? AND queue_date=? AND status='CALLED'",Integer.class,doctorId,date);
    }
    public int transition(long id,String from,String to) {
        return db.update("UPDATE queue_token SET status=?,called_at=CASE WHEN ?='CALLED' THEN SYSUTCDATETIME() ELSE called_at END,completed_at=CASE WHEN ?<>'CALLED' THEN SYSUTCDATETIME() ELSE completed_at END WHERE id=? AND status=?",to,to,to,id,from);
    }
    public void appointmentStatus(long id,String status) { db.update("UPDATE appointment SET status=?,updated_at=SYSUTCDATETIME() WHERE id=?",status,id); }
}
