package lk.sunshine.smartcare.features.report;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class ReportManagementRepository {
    private final JdbcTemplate db;
    public ReportManagementRepository(JdbcTemplate db) { this.db=db; }

    private static final String TEMPLATE_COLUMNS="""
        t.id,t.owner_user_id,u.full_name AS owner_name,t.name,t.description,t.visibility,t.report_type,t.date_mode,
        t.from_date,t.to_date,t.doctor_id,d.display_name AS doctor_name,t.specialty_id,sp.name AS specialty_name,
        t.department,t.branch_id,b.name AS branch_name,t.configuration_json,t.created_at,t.updated_at
        """;

    public List<Map<String,Object>> templates(long ownerId,String role) {
        return db.queryForList("""
            SELECT %s FROM report_template t JOIN app_user u ON u.id=t.owner_user_id
            LEFT JOIN doctor d ON d.id=t.doctor_id LEFT JOIN specialty sp ON sp.id=t.specialty_id LEFT JOIN branch b ON b.id=t.branch_id
            WHERE t.owner_user_id=? OR t.visibility='PUBLIC' OR (t.visibility='TEAM' AND ? IN ('ADMIN','MANAGER'))
            ORDER BY CASE WHEN t.owner_user_id=? THEN 0 ELSE 1 END,t.name
            """.formatted(TEMPLATE_COLUMNS),ownerId,role,ownerId);
    }
    public List<Map<String,Object>> templates(long ownerId) { return templates(ownerId,"MANAGER"); }

    public Map<String,Object> template(long ownerId,String role,long id) {
        return db.queryForMap("""
            SELECT %s FROM report_template t JOIN app_user u ON u.id=t.owner_user_id
            LEFT JOIN doctor d ON d.id=t.doctor_id LEFT JOIN specialty sp ON sp.id=t.specialty_id LEFT JOIN branch b ON b.id=t.branch_id
            WHERE t.id=? AND (t.owner_user_id=? OR t.visibility='PUBLIC' OR (t.visibility='TEAM' AND ? IN ('ADMIN','MANAGER')))
            """.formatted(TEMPLATE_COLUMNS),id,ownerId,role);
    }
    public Map<String,Object> template(long ownerId,long id) { return db.queryForMap("SELECT * FROM report_template WHERE owner_user_id=? AND id=?",ownerId,id); }
    public int templateNameCount(long ownerId,String name,Long exceptId) { return db.queryForObject("SELECT COUNT(*) FROM report_template WHERE owner_user_id=? AND LOWER(name)=LOWER(?) AND (? IS NULL OR id<>?)",Integer.class,ownerId,name,exceptId,exceptId); }

    public long createTemplate(long ownerId,ReportController.TemplateInput p,String name,String department,String configuration) {
        return db.queryForObject("""
            INSERT INTO report_template(owner_user_id,name,description,visibility,report_type,date_mode,from_date,to_date,doctor_id,specialty_id,department,branch_id,configuration_json)
            OUTPUT INSERTED.id VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,Long.class,ownerId,name,p.description(),p.visibility(),p.reportType(),p.dateMode(),p.from(),p.to(),p.doctorId(),p.specialtyId(),department,p.branchId(),configuration);
    }
    public long createTemplate(long ownerId,ReportController.TemplateInput p,String name,String department) { return createTemplate(ownerId,p,name,department,"{}"); }
    public int updateTemplate(long ownerId,long id,ReportController.TemplateInput p,String name,String department,String configuration) {
        return db.update("""
            UPDATE report_template SET name=?,description=?,visibility=?,report_type=?,date_mode=?,from_date=?,to_date=?,doctor_id=?,
                specialty_id=?,department=?,branch_id=?,configuration_json=?,updated_at=SYSUTCDATETIME() WHERE owner_user_id=? AND id=?
            """,name,p.description(),p.visibility(),p.reportType(),p.dateMode(),p.from(),p.to(),p.doctorId(),p.specialtyId(),department,p.branchId(),configuration,ownerId,id);
    }
    public int activeSchedulesForTemplate(long ownerId,long templateId) { return db.queryForObject("SELECT COUNT(*) FROM report_schedule WHERE owner_user_id=? AND template_id=? AND active=1",Integer.class,ownerId,templateId); }
    public int deleteTemplate(long ownerId,long templateId) {
        db.update("UPDATE generated_report SET template_id=NULL,schedule_id=NULL WHERE owner_user_id=? AND (template_id=? OR schedule_id IN (SELECT id FROM report_schedule WHERE template_id=?))",ownerId,templateId,templateId);
        db.update("UPDATE generated_report_file SET template_id=NULL,schedule_id=NULL WHERE owner_user_id=? AND (template_id=? OR schedule_id IN (SELECT id FROM report_schedule WHERE template_id=?))",ownerId,templateId,templateId);
        db.update("DELETE FROM report_schedule WHERE owner_user_id=? AND template_id=? AND active=0",ownerId,templateId);
        return db.update("DELETE FROM report_template WHERE owner_user_id=? AND id=?",ownerId,templateId);
    }

    public List<Map<String,Object>> schedules(long ownerId) {
        return db.queryForList("""
            SELECT s.id,s.template_id,t.name AS template_name,s.schedule_name,s.frequency,s.delivery_channel,s.recipients,
                s.export_format,s.formats_json,s.timezone,s.recurrence_json,s.email_subject,s.email_message,s.is_template_default,
                s.active,s.next_run_at,s.last_run_at,s.last_status,s.last_error,s.created_at,s.updated_at
            FROM report_schedule s JOIN report_template t ON t.id=s.template_id WHERE s.owner_user_id=?
            ORDER BY s.active DESC,s.next_run_at,s.id
            """,ownerId);
    }
    public Map<String,Object> schedule(long ownerId,long id) { return db.queryForMap("SELECT * FROM report_schedule WHERE owner_user_id=? AND id=?",ownerId,id); }
    public Map<String,Object> defaultSchedule(long ownerId,long templateId) {
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM report_schedule WHERE owner_user_id=? AND template_id=? AND is_template_default=1",ownerId,templateId);
        return rows.isEmpty()?null:rows.get(0);
    }
    public int activeScheduleCount(long ownerId,Long exceptId) { return db.queryForObject("SELECT COUNT(*) FROM report_schedule WHERE owner_user_id=? AND active=1 AND (? IS NULL OR id<>?)",Integer.class,ownerId,exceptId,exceptId); }
    public long createSchedule(long ownerId,ReportController.ScheduleInput p,String name,String recipients,String formats,String recurrence) {
        String first=p.formats().get(0);
        return db.queryForObject("""
            INSERT INTO report_schedule(template_id,owner_user_id,schedule_name,frequency,delivery_channel,recipients,export_format,formats_json,
                timezone,recurrence_json,email_subject,email_message,is_template_default,active,next_run_at)
            OUTPUT INSERTED.id VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,Long.class,p.templateId(),ownerId,name,p.frequency(),p.deliveryChannel(),recipients,first,formats,p.timezone(),recurrence,p.emailSubject(),p.emailMessage(),p.templateDefault(),p.active(),p.nextRunAt());
    }
    public long createSchedule(long ownerId,ReportController.ScheduleInput p,String recipients,LocalDateTime ignored) { return createSchedule(ownerId,p,p.name()==null?"Report delivery":p.name(),recipients,"[\""+p.formats().get(0)+"\"]","{}"); }
    public int updateSchedule(long ownerId,long id,ReportController.ScheduleInput p,String name,String recipients,String formats,String recurrence) {
        return db.update("""
            UPDATE report_schedule SET template_id=?,schedule_name=?,frequency=?,delivery_channel=?,recipients=?,export_format=?,formats_json=?,timezone=?,
                recurrence_json=?,email_subject=?,email_message=?,is_template_default=?,active=?,next_run_at=?,updated_at=SYSUTCDATETIME()
            WHERE owner_user_id=? AND id=?
            """,p.templateId(),name,p.frequency(),p.deliveryChannel(),recipients,p.formats().get(0),formats,p.timezone(),recurrence,p.emailSubject(),p.emailMessage(),p.templateDefault(),p.active(),p.nextRunAt(),ownerId,id);
    }
    public int setScheduleActive(long ownerId,long id,boolean active) { return db.update("UPDATE report_schedule SET active=?,updated_at=SYSUTCDATETIME() WHERE owner_user_id=? AND id=?",active,ownerId,id); }
    public int deleteSchedule(long ownerId,long id) {
        db.update("UPDATE generated_report SET schedule_id=NULL WHERE owner_user_id=? AND schedule_id=?",ownerId,id);
        db.update("UPDATE generated_report_file SET schedule_id=NULL WHERE owner_user_id=? AND schedule_id=?",ownerId,id);
        return db.update("DELETE FROM report_schedule WHERE owner_user_id=? AND id=?",ownerId,id);
    }
    public List<Map<String,Object>> activeSchedules() {
        return db.queryForList("""
            SELECT s.id AS schedule_id,s.owner_user_id,s.schedule_name,s.frequency,s.delivery_channel,s.recipients,s.export_format,s.formats_json,
                s.timezone,s.next_run_at,t.id AS template_id,t.name AS template_name,t.report_type,t.date_mode,t.from_date,t.to_date,t.doctor_id,
                t.specialty_id,t.department,t.branch_id,t.configuration_json,u.role AS owner_role,u.active AS owner_active
            FROM report_schedule s JOIN report_template t ON t.id=s.template_id JOIN app_user u ON u.id=s.owner_user_id
            WHERE s.active=1 ORDER BY s.next_run_at
            """);
    }
    public void scheduleSucceeded(long id,LocalDateTime ranAt,LocalDateTime nextRun) { db.update("UPDATE report_schedule SET last_run_at=?,last_status='DELIVERED',last_error=NULL,next_run_at=?,updated_at=SYSUTCDATETIME() WHERE id=?",ranAt,nextRun,id); }
    public void scheduleFailed(long id,LocalDateTime ranAt,LocalDateTime nextRun,String error) { db.update("UPDATE report_schedule SET last_run_at=?,last_status='FAILED',last_error=?,next_run_at=?,updated_at=SYSUTCDATETIME() WHERE id=?",ranAt,error,nextRun,id); }

    public long addHistory(long ownerId,Long templateId,Long scheduleId,String name,String source,String criteria,String result) {
        return db.queryForObject("INSERT INTO generated_report(owner_user_id,template_id,schedule_id,report_name,source,criteria_json,result_json) OUTPUT INSERTED.id VALUES(?,?,?,?,?,?,?)",Long.class,ownerId,templateId,scheduleId,name,source,criteria,result);
    }
    public List<Map<String,Object>> history(long ownerId) { return db.queryForList("SELECT id,template_id,schedule_id,report_name,source,criteria_json,status,created_at FROM generated_report WHERE owner_user_id=? ORDER BY created_at DESC",ownerId); }
    public Map<String,Object> historyItem(long ownerId,long id) { return db.queryForMap("SELECT * FROM generated_report WHERE owner_user_id=? AND id=?",ownerId,id); }
    public List<Map<String,Object>> historyFiles(long ownerId,long id) { return db.queryForList("SELECT id,storage_path FROM generated_report_file WHERE owner_user_id=? AND report_id=?",ownerId,id); }
    public int deleteHistory(long ownerId,long id) { db.update("DELETE FROM generated_report_file WHERE owner_user_id=? AND report_id=?",ownerId,id);return db.update("DELETE FROM generated_report WHERE owner_user_id=? AND id=?",ownerId,id); }

    public long addFile(long ownerId,Long templateId,Long scheduleId,Long reportId,String reportType,String format,String fileName,String path,LocalDateTime expiresAt) {
        return db.queryForObject("INSERT INTO generated_report_file(owner_user_id,template_id,schedule_id,report_id,report_type,export_format,file_name,storage_path,expires_at) OUTPUT INSERTED.id VALUES(?,?,?,?,?,?,?,?,?)",Long.class,ownerId,templateId,scheduleId,reportId,reportType,format,fileName,path,expiresAt);
    }
    public List<Map<String,Object>> files(long ownerId) { return db.queryForList("SELECT f.id,f.report_id,f.template_id,t.name AS template_name,f.schedule_id,f.report_type,f.export_format,f.file_name,f.created_at,f.expires_at FROM generated_report_file f LEFT JOIN report_template t ON t.id=f.template_id WHERE f.owner_user_id=? ORDER BY f.created_at DESC",ownerId); }
    public Map<String,Object> file(long ownerId,long id) { return db.queryForMap("SELECT id,file_name,storage_path,export_format,expires_at FROM generated_report_file WHERE owner_user_id=? AND id=?",ownerId,id); }
    public int deleteFile(long ownerId,long id) { return db.update("DELETE FROM generated_report_file WHERE owner_user_id=? AND id=?",ownerId,id); }
    public List<Map<String,Object>> expiredFiles(LocalDateTime now) { return db.queryForList("SELECT id,owner_user_id,storage_path FROM generated_report_file WHERE expires_at<=?",now); }

    public boolean doctorExists(Long id) { return id==null||db.queryForObject("SELECT COUNT(*) FROM doctor WHERE id=?",Integer.class,id)>0; }
    public boolean specialtyExists(Long id) { return id==null||db.queryForObject("SELECT COUNT(*) FROM specialty WHERE id=?",Integer.class,id)>0; }
    public boolean branchExists(Long id) { return id==null||db.queryForObject("SELECT COUNT(*) FROM branch WHERE id=?",Integer.class,id)>0; }
    public boolean departmentExists(String department) { return department==null||db.queryForObject("SELECT COUNT(*) FROM doctor WHERE LOWER(department)=LOWER(?)",Integer.class,department)>0; }
}
