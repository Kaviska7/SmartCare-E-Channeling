package lk.sunshine.smartcare.features.report;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class ReportRepository {
    private final JdbcTemplate db;
    public ReportRepository(JdbcTemplate db) { this.db=db; }

    public List<Map<String,Object>> detailRows(LocalDate from,LocalDate to,Long doctorId,Long branchId,Long specialtyId,String department,boolean bookingDates) {
        String date=bookingDates?"CAST(DATEADD(MINUTE,330,a.booked_at) AS DATE)":"CAST(s.starts_at AS DATE)";
        return db.queryForList("""
            SELECT d.id AS doctor_id,d.display_name AS doctor,b.id AS branch_id,b.name AS branch,
                sp.id AS specialty_id,sp.name AS specialty,d.department,COUNT(a.id) AS appointments,
                SUM(CASE WHEN a.status='CANCELLED' THEN 1 ELSE 0 END) AS cancellations,
                SUM(CASE WHEN a.status='NO_SHOW' THEN 1 ELSE 0 END) AS no_shows,
                COALESCE(SUM(CASE WHEN dp.status IN ('DEMO_PAID','DEMO_REFUNDED') THEN dp.amount ELSE 0 END),0) AS gross_revenue,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_REFUNDED' THEN dp.amount ELSE 0 END),0) AS refunded_revenue,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_PAID' THEN dp.amount ELSE 0 END),0) AS net_revenue,
                COUNT(DISTINCT CASE WHEN CAST(s.starts_at AS DATE) BETWEEN ? AND ? THEN s.id END) AS total_slots,
                COUNT(DISTINCT CASE WHEN a.status NOT IN ('CANCELLED','NO_SHOW') THEN s.id END) AS used_slots
            FROM doctor d JOIN specialty sp ON sp.id=d.specialty_id
            JOIN (SELECT doctor_id,branch_id FROM doctor_branch UNION SELECT doctor_id,branch_id FROM slot) assignment ON assignment.doctor_id=d.id
            JOIN branch b ON b.id=assignment.branch_id
            LEFT JOIN slot s ON s.doctor_id=d.id AND s.branch_id=b.id
            LEFT JOIN appointment a ON a.slot_id=s.id AND
            """+date+"""
             BETWEEN ? AND ?
            LEFT JOIN demo_payment dp ON dp.appointment_id=a.id
            WHERE (? IS NULL OR d.id=?) AND (? IS NULL OR b.id=?) AND (? IS NULL OR d.specialty_id=?)
                AND (? IS NULL OR LOWER(d.department)=LOWER(?))
            GROUP BY d.id,d.display_name,b.id,b.name,sp.id,sp.name,d.department
            """,Date.valueOf(from),Date.valueOf(to),Date.valueOf(from),Date.valueOf(to),doctorId,doctorId,branchId,branchId,specialtyId,specialtyId,department,department);
    }

    public Map<String,Object> totals(LocalDate from,LocalDate to,Long doctorId,Long branchId) {
        return db.queryForMap("""
            SELECT COUNT(a.id) AS bookings,
                COALESCE(SUM(CASE WHEN a.status='CANCELLED' THEN 1 ELSE 0 END),0) AS cancellations,
                COALESCE(SUM(CASE WHEN a.status='NO_SHOW' THEN 1 ELSE 0 END),0) AS no_shows,
                COALESCE(SUM(CASE WHEN a.status IN ('CONFIRMED','CHECKED_IN','CALLED') THEN a.fee_at_booking ELSE 0 END),0) AS booked_estimated_fees,
                COALESCE(SUM(CASE WHEN a.status='COMPLETED' THEN a.fee_at_booking ELSE 0 END),0) AS completed_estimated_fees,
                COALESCE(SUM(CASE WHEN dp.status IN ('DEMO_PAID','DEMO_REFUNDED') THEN dp.amount ELSE 0 END),0) AS gross_simulated_revenue,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_REFUNDED' THEN dp.amount ELSE 0 END),0) AS refunded_simulated_amount,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_PAID' THEN dp.amount ELSE 0 END),0) AS simulated_revenue,
                COALESCE(SUM(CASE WHEN dp.status IN ('DEMO_PAID','DEMO_REFUNDED') THEN 1 ELSE 0 END),0) AS demo_payment_count,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_REFUNDED' THEN 1 ELSE 0 END),0) AS refund_count
            FROM appointment a JOIN slot s ON s.id=a.slot_id
            LEFT JOIN demo_payment dp ON dp.appointment_id=a.id
            WHERE CAST(s.starts_at AS DATE) BETWEEN ? AND ?
                AND (? IS NULL OR s.doctor_id=?) AND (? IS NULL OR a.branch_id=?)
            """,Date.valueOf(from),Date.valueOf(to),doctorId,doctorId,branchId,branchId);
    }

    public Map<String,Object> cancellationTotals(LocalDate from,LocalDate to,Long doctorId,Long branchId) {
        return db.queryForMap("""
            SELECT COUNT(a.id) AS bookings,
                COALESCE(SUM(CASE WHEN a.status='CANCELLED' THEN 1 ELSE 0 END),0) AS cancellations,
                COALESCE(SUM(CASE WHEN a.status='NO_SHOW' THEN 1 ELSE 0 END),0) AS no_shows,
                COALESCE(SUM(CASE WHEN a.status IN ('CONFIRMED','CHECKED_IN','CALLED') THEN a.fee_at_booking ELSE 0 END),0) AS booked_estimated_fees,
                COALESCE(SUM(CASE WHEN a.status='COMPLETED' THEN a.fee_at_booking ELSE 0 END),0) AS completed_estimated_fees,
                COALESCE(SUM(CASE WHEN dp.status IN ('DEMO_PAID','DEMO_REFUNDED') THEN dp.amount ELSE 0 END),0) AS gross_simulated_revenue,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_REFUNDED' THEN dp.amount ELSE 0 END),0) AS refunded_simulated_amount,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_PAID' THEN dp.amount ELSE 0 END),0) AS simulated_revenue,
                COALESCE(SUM(CASE WHEN dp.status IN ('DEMO_PAID','DEMO_REFUNDED') THEN 1 ELSE 0 END),0) AS demo_payment_count,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_REFUNDED' THEN 1 ELSE 0 END),0) AS refund_count
            FROM appointment a JOIN slot s ON s.id=a.slot_id
            LEFT JOIN demo_payment dp ON dp.appointment_id=a.id
            WHERE CAST(DATEADD(MINUTE,330,a.booked_at) AS DATE) BETWEEN ? AND ?
                AND (? IS NULL OR s.doctor_id=?) AND (? IS NULL OR a.branch_id=?)
            """,Date.valueOf(from),Date.valueOf(to),doctorId,doctorId,branchId,branchId);
    }

    public List<Map<String,Object>> cancelledAppointments(LocalDate from,LocalDate to,Long doctorId,Long branchId) {
        return db.queryForList("""
            SELECT a.id AS appointment_id,u.full_name AS patient_name,d.display_name AS doctor_name,
                b.name AS branch_name,s.starts_at,CAST(DATEADD(MINUTE,330,a.booked_at) AS DATE) AS booked_on,
                CAST(DATEADD(MINUTE,330,a.updated_at) AS DATE) AS cancelled_on,
                dp.status AS payment_status,
                CASE WHEN dp.status='DEMO_REFUNDED' THEN dp.amount ELSE 0 END AS refunded_simulated_amount
            FROM appointment a JOIN patient p ON p.id=a.patient_id JOIN app_user u ON u.id=p.user_id
            JOIN slot s ON s.id=a.slot_id JOIN doctor d ON d.id=s.doctor_id
            JOIN branch b ON b.id=a.branch_id LEFT JOIN demo_payment dp ON dp.appointment_id=a.id
            WHERE a.status='CANCELLED'
                AND CAST(DATEADD(MINUTE,330,a.booked_at) AS DATE) BETWEEN ? AND ?
                AND (? IS NULL OR s.doctor_id=?) AND (? IS NULL OR a.branch_id=?)
            ORDER BY a.updated_at DESC,a.id DESC
            """,Date.valueOf(from),Date.valueOf(to),doctorId,doctorId,branchId,branchId);
    }

    public List<Map<String,Object>> doctors(LocalDate from,LocalDate to,Long doctorId,Long branchId) {
        return db.queryForList("""
            SELECT d.id,d.display_name AS doctor_name,b.name AS branch_name,
                COUNT(DISTINCT s.id) AS total_slots,
                COUNT(DISTINCT CASE WHEN a.status NOT IN ('CANCELLED','NO_SHOW') THEN s.id END) AS used_slots,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_PAID' THEN dp.amount ELSE 0 END),0) AS simulated_revenue
            FROM doctor d JOIN (SELECT doctor_id,branch_id FROM doctor_branch UNION SELECT doctor_id,branch_id FROM slot) db ON db.doctor_id=d.id JOIN branch b ON b.id=db.branch_id
            LEFT JOIN slot s ON s.doctor_id=d.id AND s.branch_id=db.branch_id AND CAST(s.starts_at AS DATE) BETWEEN ? AND ?
            LEFT JOIN appointment a ON a.slot_id=s.id
            LEFT JOIN demo_payment dp ON dp.appointment_id=a.id
            WHERE (? IS NULL OR d.id=?) AND (? IS NULL OR db.branch_id=?)
            GROUP BY d.id,d.display_name,b.name ORDER BY d.display_name
            """,Date.valueOf(from),Date.valueOf(to),doctorId,doctorId,branchId,branchId);
    }

    public List<Map<String,Object>> doctorRevenue(LocalDate from,LocalDate to,Long doctorId,Long branchId) {
        return db.queryForList("""
            SELECT d.id AS doctor_id,d.display_name AS doctor_name,b.id AS branch_id,b.name AS branch_name,
                COUNT(a.id) AS bookings,
                COALESCE(SUM(CASE WHEN dp.status IN ('DEMO_PAID','DEMO_REFUNDED') THEN dp.amount ELSE 0 END),0) AS gross_simulated_revenue,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_REFUNDED' THEN dp.amount ELSE 0 END),0) AS refunded_simulated_amount,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_PAID' THEN dp.amount ELSE 0 END),0) AS simulated_revenue
            FROM appointment a JOIN slot s ON s.id=a.slot_id
            JOIN doctor d ON d.id=s.doctor_id JOIN branch b ON b.id=a.branch_id
            LEFT JOIN demo_payment dp ON dp.appointment_id=a.id
            WHERE CAST(s.starts_at AS DATE) BETWEEN ? AND ?
                AND (? IS NULL OR s.doctor_id=?) AND (? IS NULL OR a.branch_id=?)
            GROUP BY d.id,d.display_name,b.id,b.name ORDER BY d.display_name,b.name
            """,Date.valueOf(from),Date.valueOf(to),doctorId,doctorId,branchId,branchId);
    }

    public Double averageWait(LocalDate from,LocalDate to,Long doctorId,Long branchId) {
        return db.queryForObject("""
            SELECT CAST(AVG(CAST(DATEDIFF(MINUTE,q.checked_in_at,q.called_at) AS FLOAT)) AS FLOAT)
            FROM queue_token q JOIN appointment a ON a.id=q.appointment_id
            WHERE q.called_at IS NOT NULL AND q.queue_date BETWEEN ? AND ?
                AND (? IS NULL OR q.doctor_id=?) AND (? IS NULL OR a.branch_id=?)
            """,Double.class,Date.valueOf(from),Date.valueOf(to),doctorId,doctorId,branchId,branchId);
    }

    public Map<String,Object> totals(LocalDate from,LocalDate to,Long doctorId,Long branchId,Long specialtyId,String department) {
        return db.queryForMap("""
            SELECT COUNT(a.id) AS bookings,
                COALESCE(SUM(CASE WHEN a.status='CANCELLED' THEN 1 ELSE 0 END),0) AS cancellations,
                COALESCE(SUM(CASE WHEN a.status='NO_SHOW' THEN 1 ELSE 0 END),0) AS no_shows,
                COALESCE(SUM(CASE WHEN a.status IN ('CONFIRMED','CHECKED_IN','CALLED') THEN a.fee_at_booking ELSE 0 END),0) AS booked_estimated_fees,
                COALESCE(SUM(CASE WHEN a.status='COMPLETED' THEN a.fee_at_booking ELSE 0 END),0) AS completed_estimated_fees,
                COALESCE(SUM(CASE WHEN dp.status IN ('DEMO_PAID','DEMO_REFUNDED') THEN dp.amount ELSE 0 END),0) AS gross_simulated_revenue,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_REFUNDED' THEN dp.amount ELSE 0 END),0) AS refunded_simulated_amount,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_PAID' THEN dp.amount ELSE 0 END),0) AS simulated_revenue,
                COALESCE(SUM(CASE WHEN dp.status IN ('DEMO_PAID','DEMO_REFUNDED') THEN 1 ELSE 0 END),0) AS demo_payment_count,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_REFUNDED' THEN 1 ELSE 0 END),0) AS refund_count
            FROM appointment a JOIN slot s ON s.id=a.slot_id JOIN doctor d ON d.id=s.doctor_id
            LEFT JOIN demo_payment dp ON dp.appointment_id=a.id
            WHERE CAST(s.starts_at AS DATE) BETWEEN ? AND ? AND (? IS NULL OR s.doctor_id=?)
                AND (? IS NULL OR a.branch_id=?) AND (? IS NULL OR d.specialty_id=?)
                AND (? IS NULL OR LOWER(d.department)=LOWER(?))
            """,Date.valueOf(from),Date.valueOf(to),doctorId,doctorId,branchId,branchId,specialtyId,specialtyId,department,department);
    }

    public Map<String,Object> cancellationTotals(LocalDate from,LocalDate to,Long doctorId,Long branchId,Long specialtyId,String department) {
        return db.queryForMap("""
            SELECT COUNT(a.id) AS bookings,
                COALESCE(SUM(CASE WHEN a.status='CANCELLED' THEN 1 ELSE 0 END),0) AS cancellations,
                COALESCE(SUM(CASE WHEN a.status='NO_SHOW' THEN 1 ELSE 0 END),0) AS no_shows,
                COALESCE(SUM(CASE WHEN a.status IN ('CONFIRMED','CHECKED_IN','CALLED') THEN a.fee_at_booking ELSE 0 END),0) AS booked_estimated_fees,
                COALESCE(SUM(CASE WHEN a.status='COMPLETED' THEN a.fee_at_booking ELSE 0 END),0) AS completed_estimated_fees,
                COALESCE(SUM(CASE WHEN dp.status IN ('DEMO_PAID','DEMO_REFUNDED') THEN dp.amount ELSE 0 END),0) AS gross_simulated_revenue,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_REFUNDED' THEN dp.amount ELSE 0 END),0) AS refunded_simulated_amount,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_PAID' THEN dp.amount ELSE 0 END),0) AS simulated_revenue,
                COALESCE(SUM(CASE WHEN dp.status IN ('DEMO_PAID','DEMO_REFUNDED') THEN 1 ELSE 0 END),0) AS demo_payment_count,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_REFUNDED' THEN 1 ELSE 0 END),0) AS refund_count
            FROM appointment a JOIN slot s ON s.id=a.slot_id JOIN doctor d ON d.id=s.doctor_id
            LEFT JOIN demo_payment dp ON dp.appointment_id=a.id
            WHERE CAST(DATEADD(MINUTE,330,a.booked_at) AS DATE) BETWEEN ? AND ? AND (? IS NULL OR s.doctor_id=?)
                AND (? IS NULL OR a.branch_id=?) AND (? IS NULL OR d.specialty_id=?)
                AND (? IS NULL OR LOWER(d.department)=LOWER(?))
            """,Date.valueOf(from),Date.valueOf(to),doctorId,doctorId,branchId,branchId,specialtyId,specialtyId,department,department);
    }

    public List<Map<String,Object>> cancelledAppointments(LocalDate from,LocalDate to,Long doctorId,Long branchId,Long specialtyId,String department) {
        return db.queryForList("""
            SELECT a.id AS appointment_id,u.full_name AS patient_name,d.display_name AS doctor_name,
                b.name AS branch_name,s.starts_at,CAST(DATEADD(MINUTE,330,a.booked_at) AS DATE) AS booked_on,
                CAST(DATEADD(MINUTE,330,a.updated_at) AS DATE) AS cancelled_on,dp.status AS payment_status,
                CASE WHEN dp.status='DEMO_REFUNDED' THEN dp.amount ELSE 0 END AS refunded_simulated_amount
            FROM appointment a JOIN patient p ON p.id=a.patient_id JOIN app_user u ON u.id=p.user_id
            JOIN slot s ON s.id=a.slot_id JOIN doctor d ON d.id=s.doctor_id JOIN branch b ON b.id=a.branch_id
            LEFT JOIN demo_payment dp ON dp.appointment_id=a.id
            WHERE a.status='CANCELLED' AND CAST(DATEADD(MINUTE,330,a.booked_at) AS DATE) BETWEEN ? AND ?
                AND (? IS NULL OR s.doctor_id=?) AND (? IS NULL OR a.branch_id=?) AND (? IS NULL OR d.specialty_id=?)
                AND (? IS NULL OR LOWER(d.department)=LOWER(?))
            ORDER BY a.updated_at DESC,a.id DESC
            """,Date.valueOf(from),Date.valueOf(to),doctorId,doctorId,branchId,branchId,specialtyId,specialtyId,department,department);
    }

    public List<Map<String,Object>> doctors(LocalDate from,LocalDate to,Long doctorId,Long branchId,Long specialtyId,String department) {
        return db.queryForList("""
            SELECT d.id,d.display_name AS doctor_name,b.name AS branch_name,COUNT(DISTINCT s.id) AS total_slots,
                COUNT(DISTINCT CASE WHEN a.status NOT IN ('CANCELLED','NO_SHOW') THEN s.id END) AS used_slots,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_PAID' THEN dp.amount ELSE 0 END),0) AS simulated_revenue
            FROM doctor d JOIN (SELECT doctor_id,branch_id FROM doctor_branch UNION SELECT doctor_id,branch_id FROM slot) db ON db.doctor_id=d.id JOIN branch b ON b.id=db.branch_id
            LEFT JOIN slot s ON s.doctor_id=d.id AND s.branch_id=db.branch_id AND CAST(s.starts_at AS DATE) BETWEEN ? AND ?
            LEFT JOIN appointment a ON a.slot_id=s.id LEFT JOIN demo_payment dp ON dp.appointment_id=a.id
            WHERE (? IS NULL OR d.id=?) AND (? IS NULL OR db.branch_id=?) AND (? IS NULL OR d.specialty_id=?)
                AND (? IS NULL OR LOWER(d.department)=LOWER(?))
            GROUP BY d.id,d.display_name,b.name ORDER BY d.display_name
            """,Date.valueOf(from),Date.valueOf(to),doctorId,doctorId,branchId,branchId,specialtyId,specialtyId,department,department);
    }

    public List<Map<String,Object>> doctorRevenue(LocalDate from,LocalDate to,Long doctorId,Long branchId,Long specialtyId,String department) {
        return db.queryForList("""
            SELECT d.id AS doctor_id,d.display_name AS doctor_name,b.id AS branch_id,b.name AS branch_name,
                COUNT(a.id) AS bookings,
                COALESCE(SUM(CASE WHEN dp.status IN ('DEMO_PAID','DEMO_REFUNDED') THEN dp.amount ELSE 0 END),0) AS gross_simulated_revenue,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_REFUNDED' THEN dp.amount ELSE 0 END),0) AS refunded_simulated_amount,
                COALESCE(SUM(CASE WHEN dp.status='DEMO_PAID' THEN dp.amount ELSE 0 END),0) AS simulated_revenue
            FROM appointment a JOIN slot s ON s.id=a.slot_id JOIN doctor d ON d.id=s.doctor_id
            JOIN branch b ON b.id=a.branch_id LEFT JOIN demo_payment dp ON dp.appointment_id=a.id
            WHERE CAST(s.starts_at AS DATE) BETWEEN ? AND ? AND (? IS NULL OR s.doctor_id=?)
                AND (? IS NULL OR a.branch_id=?) AND (? IS NULL OR d.specialty_id=?)
                AND (? IS NULL OR LOWER(d.department)=LOWER(?))
            GROUP BY d.id,d.display_name,b.id,b.name ORDER BY d.display_name,b.name
            """,Date.valueOf(from),Date.valueOf(to),doctorId,doctorId,branchId,branchId,specialtyId,specialtyId,department,department);
    }

    public Double averageWait(LocalDate from,LocalDate to,Long doctorId,Long branchId,Long specialtyId,String department) {
        return db.queryForObject("""
            SELECT CAST(AVG(CAST(DATEDIFF(MINUTE,q.checked_in_at,q.called_at) AS FLOAT)) AS FLOAT)
            FROM queue_token q JOIN appointment a ON a.id=q.appointment_id JOIN slot s ON s.id=a.slot_id
            JOIN doctor d ON d.id=s.doctor_id LEFT JOIN demo_payment dp ON dp.appointment_id=a.id
            WHERE q.called_at IS NOT NULL AND q.queue_date BETWEEN ? AND ? AND (? IS NULL OR q.doctor_id=?)
                AND (? IS NULL OR a.branch_id=?) AND (? IS NULL OR d.specialty_id=?)
                AND (? IS NULL OR LOWER(d.department)=LOWER(?))
            """,Double.class,Date.valueOf(from),Date.valueOf(to),doctorId,doctorId,branchId,branchId,specialtyId,specialtyId,department,department);
    }
}
