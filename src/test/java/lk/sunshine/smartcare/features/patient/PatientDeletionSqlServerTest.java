package lk.sunshine.smartcare.features.patient;

import lk.sunshine.smartcare.shared.CurrentUser;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.*;

/** Opt-in, EMPTY disposable database only; never targets the application database. */
@EnabledIfSystemProperty(named="patient.sqlserver.tests", matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientDeletionSqlServerTest {
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private PatientService service;
    private Authentication admin;
    private long branch, doctor;
    private int sequence;

    @BeforeAll void migrate() throws Exception {
        String url=System.getenv("PATIENT_TEST_URL");
        String username=System.getenv("PATIENT_TEST_USERNAME"), password=System.getenv("PATIENT_TEST_PASSWORD");
        if(Boolean.getBoolean("patient.test.local")) {
            var config=new Properties();
            try(var reader=Files.newBufferedReader(Path.of("config/application-local.properties"))) { config.load(reader); }
            url="jdbc:sqlserver://localhost:1433;databaseName=smartcare_patient_delete_test_20261005;encrypt=false;trustServerCertificate=true";
            username=config.getProperty("spring.datasource.username");password=config.getProperty("spring.datasource.password");
        }
        if(url==null || !url.toLowerCase(Locale.ROOT).contains("databasename=smartcare_patient_delete_test_"))
            throw new IllegalArgumentException("Use a disposable database named smartcare_patient_delete_test_*");
        var source=new DriverManagerDataSource(url,username,password);
        db=new JdbcTemplate(source);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys.tables WHERE is_ms_shipped=0",Integer.class)).isZero();
        Flyway.configure().dataSource(source).load().migrate();
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(15);
        branch=db.queryForObject("SELECT TOP 1 id FROM branch",Long.class);
        long specialty=id("INSERT INTO specialty(name) OUTPUT INSERTED.id VALUES('Patient deletion test')");
        doctor=id("INSERT INTO doctor(display_name,specialty_id,department) OUTPUT INSERTED.id VALUES('Test doctor',?,'Test')",specialty);
        id("INSERT INTO app_user(email,password_hash,role,full_name) OUTPUT INSERTED.id VALUES('delete-admin@example.invalid','unused','ADMIN','Test admin')");
        admin=new UsernamePasswordAuthenticationToken("delete-admin@example.invalid","unused",List.of());
        service=new PatientService(new PatientRepository(db),new BCryptPasswordEncoder(),new CurrentUser(db));
    }

    private long id(String sql,Object... args) { return db.queryForObject(sql,Long.class,args); }
    private long user(boolean active) {
        return id("INSERT INTO app_user(email,password_hash,role,full_name,active) OUTPUT INSERTED.id VALUES(?,'unused','PATIENT','Test patient',?)",
                "delete-patient-"+(++sequence)+"@example.invalid",active);
    }
    private long patient(long user) {
        return id("INSERT INTO patient(user_id,nic,date_of_birth,phone) OUTPUT INSERTED.id VALUES(?,?,'1994-01-01','0700000000')",user,"DELETE-"+user);
    }
    private long appointment(long patient) {
        var start=LocalDateTime.now().plusDays(3).plusMinutes(++sequence);
        long slot=id("INSERT INTO slot(doctor_id,branch_id,starts_at,ends_at,status) OUTPUT INSERTED.id VALUES(?,?,?,?,'BOOKED')",doctor,branch,start,start.plusMinutes(30));
        return id("INSERT INTO appointment(patient_id,slot_id,status,fee_at_booking,branch_id) OUTPUT INSERTED.id VALUES(?,?,'COMPLETED',100,?)",patient,slot,branch);
    }
    private record Fixture(long user,long patient,long notification,long audit,long ownReview,long moderatedReview,long token) {}
    private Fixture fixture(boolean active) {
        long target=user(active),patient=patient(target),otherPatient=patient(user(true));
        long notification=id("INSERT INTO notification(user_id,type,message) OUTPUT INSERTED.id VALUES(?,'TEST','Patient deletion test')",target);
        long audit=id("INSERT INTO audit_event(actor_user_id,action,entity_name,entity_id) OUTPUT INSERTED.id VALUES(?,'TEST_ACTION','patient',?)",target,patient);
        // Legacy cross-linked review: the FK schema permits this without a target-owned appointment.
        long ownReview=id("INSERT INTO appointment_review(appointment_id,patient_id,rating,comment) OUTPUT INSERTED.id VALUES(?,?,5,'Own legacy review')",appointment(otherPatient),patient);
        long moderatedReview=id("INSERT INTO appointment_review(appointment_id,patient_id,rating,deleted_at,deleted_by_user_id) OUTPUT INSERTED.id VALUES(?,?,4,SYSUTCDATETIME(),?)",appointment(otherPatient),otherPatient,target);
        long token=id("INSERT INTO password_reset_token(user_id,hashed_code,expires_at) OUTPUT INSERTED.id VALUES(?,'test-hash',DATEADD(minute,10,SYSUTCDATETIME()))",target);
        db.update("INSERT INTO emergency_contact(patient_id,full_name,relationship,phone) VALUES(?,'Contact','Family','0700000000')",patient);
        return new Fixture(target,patient,notification,audit,ownReview,moderatedReview,token);
    }
    private int count(String table,long id) { return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE id=?",Integer.class,id); }
    private void delete(long user) { tx.executeWithoutResult(status -> service.permanentlyDeleteByAdmin(admin,user)); }

    @Test void nonAppointmentHistoryDoesNotPreventDeletionAndOtherHistorySurvives() {
        var f=fixture(false);
        delete(f.user());
        assertThat(count("app_user",f.user())).isZero();
        assertThat(count("patient",f.patient())).isZero();
        assertThat(count("notification",f.notification())).isZero();
        assertThat(count("password_reset_token",f.token())).isZero();
        assertThat(count("appointment_review",f.ownReview())).isZero();
        assertThat(db.queryForMap("SELECT actor_user_id,action FROM audit_event WHERE id=?",f.audit()))
                .containsEntry("actor_user_id",null).containsEntry("action","TEST_ACTION");
        assertThat(db.queryForMap("SELECT deleted_by_user_id,rating,deleted_at FROM appointment_review WHERE id=?",f.moderatedReview()))
                .containsEntry("deleted_by_user_id",null).containsEntry("rating",4).doesNotContainEntry("deleted_at",null);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM emergency_contact WHERE patient_id=?",Integer.class,f.patient())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='PERMANENT_DELETE_PATIENT' AND entity_id=?",Integer.class,f.user())).isEqualTo(1);
    }

    @Test void ownedAppointmentStillBlocksDeletionWithoutCleaningOtherHistory() {
        var f=fixture(false);
        long appointment=appointment(f.patient());
        assertThatThrownBy(() -> delete(f.user())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("appointment, payment, or queue history");
        assertThat(count("appointment",appointment)).isEqualTo(1);
        assertUnchanged(f);
    }

    @Test void activePatientStillMustBeDeactivated() {
        var f=fixture(true);
        assertThatThrownBy(() -> delete(f.user())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Deactivate");
        assertUnchanged(f);
    }

    @Test void unexpectedForeignKeyFailureRollsBackEveryCleanup() {
        var f=fixture(false);
        // An unexpected legacy report owner remains protected; do not cascade into reports.
        db.update("INSERT INTO generated_report(owner_user_id,report_name,source,criteria_json,result_json) VALUES(?,'Legacy owner','MANUAL','{}','{}')",f.user());
        assertThatThrownBy(() -> delete(f.user())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("protected history");
        assertUnchanged(f);
        assertThat(count("appointment_review",f.ownReview())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT deleted_by_user_id FROM appointment_review WHERE id=?",Long.class,f.moderatedReview())).isEqualTo(f.user());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM emergency_contact WHERE patient_id=?",Integer.class,f.patient())).isEqualTo(1);
    }

    private void assertUnchanged(Fixture f) {
        assertThat(count("app_user",f.user())).isEqualTo(1);
        assertThat(count("patient",f.patient())).isEqualTo(1);
        assertThat(count("notification",f.notification())).isEqualTo(1);
        assertThat(count("password_reset_token",f.token())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT actor_user_id FROM audit_event WHERE id=?",Long.class,f.audit())).isEqualTo(f.user());
    }
}
