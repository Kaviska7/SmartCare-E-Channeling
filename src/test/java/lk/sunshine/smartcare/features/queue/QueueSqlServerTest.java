package lk.sunshine.smartcare.features.queue;

import lk.sunshine.smartcare.features.appointment.AppointmentRepository;
import lk.sunshine.smartcare.features.appointment.AppointmentService;
import lk.sunshine.smartcare.shared.CurrentUser;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Opt-in tests against an EMPTY, disposable SQL Server database, never the application database.
 * Supply queue.test.url, queue.test.username and queue.test.password through environment variables
 * QUEUE_TEST_URL, QUEUE_TEST_USERNAME, QUEUE_TEST_PASSWORD. Or use the local development login
 * by setting queue.test.local=true (the database name remains fixed to the disposable test database).
 */
@EnabledIfSystemProperty(named="queue.sqlserver.tests",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QueueSqlServerTest {
    private JdbcTemplate db;
    private DriverManagerDataSource source;
    private TransactionTemplate transaction;
    private QueueRepository repo;
    private QueueService service;
    private final Authentication auth=mock(Authentication.class);
    private final LocalDate date=LocalDate.now(ZoneId.of("Asia/Colombo"));
    private long doctor,patient,branch,otherBranch;
    private int minute;
    private long legacyAppointment;

    @BeforeAll void migrateExistingSchema() throws Exception {
        String url=System.getenv("QUEUE_TEST_URL"),username=System.getenv("QUEUE_TEST_USERNAME"),password=System.getenv("QUEUE_TEST_PASSWORD");
        if(Boolean.getBoolean("queue.test.local")) {
            Properties config=new Properties();
            try(Reader reader=Files.newBufferedReader(Path.of("config/application-local.properties"))) { config.load(reader); }
            url="jdbc:sqlserver://localhost:1433;databaseName=smartcare_queue_test_20261005;encrypt=false;trustServerCertificate=true";
            username=config.getProperty("spring.datasource.username");password=config.getProperty("spring.datasource.password");
        }
        if(url==null||!url.toLowerCase(Locale.ROOT).contains("databasename=smartcare_queue_test_"))
            throw new IllegalArgumentException("Use a disposable database named smartcare_queue_test_*");
        source=new DriverManagerDataSource(url,username,password);db=new JdbcTemplate(source);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys.tables WHERE is_ms_shipped=0",Integer.class))
            .as("Test database must be empty").isZero();
        Flyway.configure().dataSource(source).target("19").load().migrate();
        branch=db.queryForObject("SELECT TOP 1 id FROM branch",Long.class);
        otherBranch=id("INSERT INTO branch(name) OUTPUT INSERTED.id VALUES('Queue test branch')");
        long specialty=id("INSERT INTO specialty(name) OUTPUT INSERTED.id VALUES('Queue test')");
        doctor=id("INSERT INTO doctor(display_name,specialty_id,department) OUTPUT INSERTED.id VALUES('Queue doctor',?,'Test')",specialty);
        db.update("INSERT INTO doctor_branch(doctor_id,branch_id,fee) VALUES(?,?,100),(?,?,100)",doctor,branch,doctor,otherBranch);
        long user=id("INSERT INTO app_user(email,password_hash,role,full_name) OUTPUT INSERTED.id VALUES('queue-test@example.invalid','unused','PATIENT','Queue patient')");
        patient=id("INSERT INTO patient(user_id,nic,date_of_birth,phone) OUTPUT INSERTED.id VALUES(?,'QUEUE-TEST','2000-01-01','000')",user);
        legacyAppointment=appointment(branch);
        db.update("INSERT INTO queue_token(appointment_id,doctor_id,queue_date,token_number) VALUES(?,?,?,1)",legacyAppointment,doctor,date);
        Flyway.configure().dataSource(source).load().migrate();
        repo=new QueueRepository(db);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(15);
        CurrentUser users=mock(CurrentUser.class);when(users.role(auth)).thenReturn("RECEPTION");
        service=new QueueService(repo,new AppointmentRepository(db),users,mock(ApplicationEventPublisher.class),
            Clock.fixed(date.atTime(12,0).atZone(ZoneId.of("Asia/Colombo")).toInstant(),ZoneOffset.UTC));
    }
    private long id(String sql,Object...args) { return db.queryForObject(sql,Long.class,args); }
    private long slot(long atBranch,LocalDate day,String status) {
        LocalDateTime starts=day.atTime(23,0).plusMinutes(minute++);
        return id("INSERT INTO slot(doctor_id,branch_id,starts_at,ends_at,status) OUTPUT INSERTED.id VALUES(?,?,?,?,?)",
            doctor,atBranch,starts,starts.plusMinutes(1),status);
    }
    private long appointment(long atBranch) {
        return id("INSERT INTO appointment(patient_id,slot_id,branch_id,status,fee_at_booking) OUTPUT INSERTED.id VALUES(?,?,?,'CONFIRMED',100)",
            patient,slot(atBranch,date,"BOOKED"),atBranch);
    }
    private <T> T tx(Supplier<T> action) { return transaction.execute(status->action.get()); }
    private long tokenFor(long appointment) { return id("SELECT TOP 1 id FROM queue_token WHERE appointment_id=? ORDER BY id DESC",appointment); }
    private String status(long token) { return db.queryForObject("SELECT status FROM queue_token WHERE id=?",String.class,token); }
    private List<Boolean> race(Runnable first,Runnable second) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> tasks=new ArrayList<>();
            for(Runnable action:List.of(first,second))tasks.add(pool.submit(()->{
                start.await();
                try { tx(()->{action.run();return true;});return true; }
                catch(ResponseStatusException expected) { return false; }
            }));
            start.countDown();List<Boolean> result=new ArrayList<>();
            for(Future<Boolean> task:tasks)result.add(task.get(20,TimeUnit.SECONDS));
            return result;
        } finally { pool.shutdownNow(); }
    }
    @Test @Order(1) void migrationBackfillsSnapshotAndAllowsOnlyOneActiveToken() {
        Map<String,Object> row=repo.token(tokenFor(legacyAppointment));
        assertThat(((Number)row.get("branch_id")).longValue()).isEqualTo(branch);
        assertThat(row.get("appointment_starts_at")).isNotNull();
        assertThatThrownBy(()->repo.add(legacyAppointment,doctor,2,date)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        db.update("UPDATE queue_token SET status='RESCHEDULED' WHERE appointment_id=?",legacyAppointment);
        db.update("UPDATE appointment SET status='CONFIRMED' WHERE id=?",legacyAppointment);
        assertThat(tx(()->service.checkIn(auth,legacyAppointment))).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM queue_token WHERE appointment_id=?",Integer.class,legacyAppointment)).isEqualTo(2);
        tx(()->{service.cancel(auth,tokenFor(legacyAppointment));return null;});
    }
    @Test @Order(2) void concurrentCheckInsHaveUniqueIncreasingNumbersAndRejectDuplicate() throws Exception {
        long a=appointment(branch),b=appointment(branch);
        assertThat(race(()->service.checkIn(auth,a),()->service.checkIn(auth,b))).containsOnly(true);
        assertThat(db.queryForList("SELECT token_number FROM queue_token WHERE appointment_id IN (?,?) ORDER BY token_number",Integer.class,a,b)).containsExactly(3,4);
        long duplicate=appointment(branch);
        assertThat(race(()->service.checkIn(auth,duplicate),()->service.checkIn(auth,duplicate))).containsExactlyInAnyOrder(true,false);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM queue_token WHERE appointment_id=?",Integer.class,duplicate)).isEqualTo(1);
    }
    @Test @Order(3) void concurrentCallsAcrossBranchesCallOnePatientAndRequireManualAdvance() throws Exception {
        long a=appointment(otherBranch);tx(()->service.checkIn(auth,a));
        assertThat(race(()->service.callNext(auth,doctor,branch,date),()->service.callNext(auth,doctor,otherBranch,date))).containsExactlyInAnyOrder(true,false);
        assertThat(repo.activeCalled(doctor,date)).isEqualTo(1);
        long called=id("SELECT id FROM queue_token WHERE status='CALLED'");
        tx(()->{service.finish(auth,called,false);return null;});
        assertThat(repo.activeCalled(doctor,date)).isZero();
        assertThat(status(called)).isEqualTo("COMPLETED");
        Long next=tx(()->service.callNext(auth,doctor,branch,date));assertThat(next).isNotNull();
        tx(()->{service.finish(auth,next,true);return null;});
        assertThat(status(next)).isEqualTo("SKIPPED");
    }
    @Test @Order(4) void deleteAndCallRaceHasOneWinnerAndKeepsConsistentAppointment() throws Exception {
        // Retire remaining waiting visits so this patient is first in the branch.
        for(Long token:db.queryForList("SELECT id FROM queue_token WHERE status='WAITING'",Long.class))
            tx(()->{service.cancel(auth,token);return null;});
        long a=appointment(branch);tx(()->service.checkIn(auth,a));long token=tokenFor(a);
        assertThat(race(()->service.cancel(auth,token),()->{
            if(service.callNext(auth,doctor,branch,date)==null)throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT);
        })).containsExactlyInAnyOrder(true,false);
        String state=status(token);
        assertThat(db.queryForObject("SELECT status FROM appointment WHERE id=?",String.class,a)).isEqualTo(state);
        if(state.equals("CALLED"))tx(()->{service.finish(auth,token,false);return null;});
        else assertThat(db.queryForObject("SELECT status FROM slot WHERE id=(SELECT slot_id FROM appointment WHERE id=?)",String.class,a)).isEqualTo("AVAILABLE");
    }
    @Test @Order(5) void reschedulePreservesOldTimeAndRollbackKeepsOriginalVisit() {
        long a=appointment(branch);tx(()->service.checkIn(auth,a));long token=tokenFor(a);
        Object original=repo.token(token).get("appointment_starts_at");
        long replacement=slot(branch,date.plusDays(1),"AVAILABLE");
        tx(()->{service.reschedule(auth,token,replacement);return null;});
        assertThat(status(token)).isEqualTo("RESCHEDULED");
        assertThat(repo.token(token).get("appointment_starts_at")).isEqualTo(original);
        assertThat(db.queryForObject("SELECT status FROM appointment WHERE id=?",String.class,a)).isEqualTo("CONFIRMED");
        assertThatThrownBy(()->tx(()->service.checkIn(auth,a))).isInstanceOf(ResponseStatusException.class);
        long b=appointment(branch);tx(()->service.checkIn(auth,b));long otherToken=tokenFor(b);
        assertThatThrownBy(()->tx(()->{service.reschedule(auth,otherToken,replacement);return null;})).isInstanceOf(ResponseStatusException.class);
        assertThat(status(otherToken)).isEqualTo("WAITING");
        assertThat(db.queryForObject("SELECT status FROM appointment WHERE id=?",String.class,b)).isEqualTo("CHECKED_IN");
    }
    @Test @Order(6) void appointmentPageCancellationCannotRaceCheckIn() throws Exception {
        CurrentUser admin=mock(CurrentUser.class);when(admin.role(auth)).thenReturn("ADMIN");
        AppointmentService appointmentService=new AppointmentService(new AppointmentRepository(db),admin,mock(ApplicationEventPublisher.class));
        long a=appointment(branch);
        assertThat(race(()->service.checkIn(auth,a),()->appointmentService.cancel(auth,a))).containsExactlyInAnyOrder(true,false);
        String state=db.queryForObject("SELECT status FROM appointment WHERE id=?",String.class,a);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM queue_token WHERE appointment_id=? AND status='WAITING'",Integer.class,a))
            .isEqualTo(state.equals("CHECKED_IN")?1:0);
    }
}
