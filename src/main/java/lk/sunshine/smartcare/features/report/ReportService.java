package lk.sunshine.smartcare.features.report;

import lk.sunshine.smartcare.shared.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportService {
    private final ReportRepository repo;private final CurrentUser users;private final Map<String,ReportExporter> exporters;
    public ReportService(ReportRepository repo,CurrentUser users,List<ReportExporter> exporters) { this.repo=repo;this.users=users;this.exporters=new LinkedHashMap<>();exporters.forEach(e->this.exporters.put(e.format(),e)); }
    public Map<String,Object> report(Authentication auth,LocalDate from,LocalDate to,Long doctorId,Long branchId,String type) {
        requireRole(users.role(auth));
        validateRange(from,to);
        if(!List.of("summary","doctor_revenue","gross_revenue","refund_cancellation").contains(type))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a report type");
        Map<String,Object> result=new LinkedHashMap<>();result.put("from",from);result.put("to",to);result.put("doctor_id",doctorId);result.put("branch_id",branchId);result.put("report_type",type);
        boolean cancellationReport="refund_cancellation".equals(type);
        result.put("date_basis",cancellationReport?"booking_date":"appointment_date");
        result.putAll(cancellationReport?repo.cancellationTotals(from,to,doctorId,branchId):repo.totals(from,to,doctorId,branchId));
        long bookings=((Number)result.get("bookings")).longValue(),cancellations=((Number)result.get("cancellations")).longValue();
        long payments=((Number)result.get("demo_payment_count")).longValue(),refunds=((Number)result.get("refund_count")).longValue();
        result.put("cancellation_rate_percent",bookings==0?0.0:Math.round(1000.0*cancellations/bookings)/10.0);
        result.put("refund_rate_percent",payments==0?0.0:Math.round(1000.0*refunds/payments)/10.0);
        if("summary".equals(type)) { result.put("average_wait_minutes",repo.averageWait(from,to,doctorId,branchId));result.put("doctors",repo.doctors(from,to,doctorId,branchId));List<Map<String,Object>> cancelled=repo.cancelledAppointments(from,to,doctorId,branchId);result.put("cancelled_appointments",cancelled);result.put("recent_cancelled_count",cancelled.size()); }
        if("doctor_revenue".equals(type))result.put("doctor_revenue",repo.doctorRevenue(from,to,doctorId,branchId));
        if(cancellationReport)result.put("cancelled_appointments",repo.cancelledAppointments(from,to,doctorId,branchId));
        return result;
    }

    public Map<String,Object> report(Authentication auth,LocalDate from,LocalDate to,Long doctorId,Long branchId,
            Long specialtyId,String department,String type) {
        return reportForAutomation(users.role(auth),from,to,doctorId,branchId,specialtyId,department,type);
    }

    public Map<String,Object> reportForAutomation(String role,LocalDate from,LocalDate to,Long doctorId,Long branchId,
            Long specialtyId,String department,String type) {
        requireRole(role);validateRange(from,to);
        if(!List.of("summary","doctor_revenue","gross_revenue","refund_cancellation").contains(type))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a report type");
        String cleanDepartment=department==null||department.isBlank()?null:department.trim();
        Map<String,Object> result=new LinkedHashMap<>();result.put("from",from);result.put("to",to);result.put("doctor_id",doctorId);result.put("branch_id",branchId);
        result.put("specialty_id",specialtyId);result.put("department",cleanDepartment);result.put("report_type",type);
        boolean cancellationReport="refund_cancellation".equals(type);result.put("date_basis",cancellationReport?"booking_date":"appointment_date");
        result.putAll(cancellationReport?repo.cancellationTotals(from,to,doctorId,branchId,specialtyId,cleanDepartment):repo.totals(from,to,doctorId,branchId,specialtyId,cleanDepartment));
        long bookings=((Number)result.get("bookings")).longValue(),cancellations=((Number)result.get("cancellations")).longValue();
        long payments=((Number)result.get("demo_payment_count")).longValue(),refunds=((Number)result.get("refund_count")).longValue();
        result.put("cancellation_rate_percent",bookings==0?0.0:Math.round(1000.0*cancellations/bookings)/10.0);
        result.put("refund_rate_percent",payments==0?0.0:Math.round(1000.0*refunds/payments)/10.0);
        Number gross=(Number)result.get("gross_simulated_revenue");
        result.put("channel_fees",gross==null?0.0:Math.round(gross.doubleValue()*10.0)/100.0);
        long days=java.time.temporal.ChronoUnit.DAYS.between(from,to)+1;
        LocalDate previousTo=from.minusDays(1),previousFrom=previousTo.minusDays(days-1);
        Map<String,Object> previous=cancellationReport?repo.cancellationTotals(previousFrom,previousTo,doctorId,branchId,specialtyId,cleanDepartment):repo.totals(previousFrom,previousTo,doctorId,branchId,specialtyId,cleanDepartment);
        double currentNet=((Number)result.get("simulated_revenue")).doubleValue(),previousNet=((Number)previous.get("simulated_revenue")).doubleValue();
        result.put("growth_percent",previousNet==0?(currentNet==0?0.0:100.0):Math.round((currentNet-previousNet)*1000.0/previousNet)/10.0);
        if("summary".equals(type)) {
            result.put("average_wait_minutes",repo.averageWait(from,to,doctorId,branchId,specialtyId,cleanDepartment));
            result.put("doctors",repo.doctors(from,to,doctorId,branchId,specialtyId,cleanDepartment));
            List<Map<String,Object>> cancelled=repo.cancelledAppointments(from,to,doctorId,branchId,specialtyId,cleanDepartment);
            result.put("cancelled_appointments",cancelled);result.put("recent_cancelled_count",cancelled.size());
        }
        if("doctor_revenue".equals(type))result.put("doctor_revenue",repo.doctorRevenue(from,to,doctorId,branchId,specialtyId,cleanDepartment));
        if(cancellationReport)result.put("cancelled_appointments",repo.cancelledAppointments(from,to,doctorId,branchId,specialtyId,cleanDepartment));
        return result;
    }

    public Map<String,Object> configuredReport(String role,LocalDate from,LocalDate to,Long doctorId,Long branchId,Long specialtyId,String department,String type,Map<String,Object> config) {
        Map<String,Object> result=reportForAutomation(role,from,to,doctorId,branchId,specialtyId,department,type);
        long days=java.time.temporal.ChronoUnit.DAYS.between(from,to)+1;
        boolean bookingDates="refund_cancellation".equals(type);
        ReportPresentation.apply(result,config,repo.detailRows(from,to,doctorId,branchId,specialtyId,department,bookingDates),repo.detailRows(from.minusDays(days),from.minusDays(1),doctorId,branchId,specialtyId,department,bookingDates));
        return result;
    }
    private void requireRole(String role) { if(!List.of("ADMIN","MANAGER").contains(role))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Manager access required"); }
    private void validateRange(LocalDate from,LocalDate to) {
        if(from==null||to==null||from.isAfter(to)||from.plusYears(5).isBefore(to))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid date range of up to five years");
    }
    public ReportExporter exporter(String format) { ReportExporter exporter=exporters.get(format);if(exporter==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose pdf, xlsx, or csv");return exporter; }
    public byte[] export(ReportExporter exporter,Map<String,Object> data) throws IOException { return exporter.export(data); }
}
