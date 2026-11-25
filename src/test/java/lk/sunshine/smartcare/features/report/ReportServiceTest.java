package lk.sunshine.smartcare.features.report;

import lk.sunshine.smartcare.shared.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ReportServiceTest {
    private final ReportRepository repo=mock(ReportRepository.class);
    private final CurrentUser users=mock(CurrentUser.class);
    private final Authentication auth=mock(Authentication.class);
    private final ReportService service=new ReportService(repo,users,List.of());
    private final LocalDate from=LocalDate.of(2026,9,1),to=LocalDate.of(2026,9,30);

    private Map<String,Object> totals() {
        return Map.of("bookings",20,"cancellations",5,"demo_payment_count",8,"refund_count",2,
            "gross_simulated_revenue",new BigDecimal("8000"),"refunded_simulated_amount",new BigDecimal("2000"),"simulated_revenue",new BigDecimal("6000"));
    }

    @Test void branchFilteredRatesUseAppointmentAndPaymentDenominators() {
        when(users.role(auth)).thenReturn("ADMIN");
        when(repo.cancellationTotals(from,to,7L,3L)).thenReturn(totals());
        when(repo.cancelledAppointments(from,to,7L,3L)).thenReturn(List.of(Map.of("appointment_id",12L,"patient_name","Patient")));

        Map<String,Object> report=service.report(auth,from,to,7L,3L,"refund_cancellation");

        assertThat(report.get("cancellation_rate_percent")).isEqualTo(25.0);
        assertThat(report.get("refund_rate_percent")).isEqualTo(25.0);
        assertThat(report.get("branch_id")).isEqualTo(3L);
        assertThat(report.get("date_basis")).isEqualTo("booking_date");
        assertThat((List<?>)report.get("cancelled_appointments")).hasSize(1);
        verify(repo).cancellationTotals(from,to,7L,3L);
    }

    @Test void doctorRevenueUsesSameBranchFilterAsTotals() {
        when(users.role(auth)).thenReturn("MANAGER");
        when(repo.totals(from,to,null,3L)).thenReturn(totals());
        when(repo.doctorRevenue(from,to,null,3L)).thenReturn(List.of(Map.of("doctor_name","Yasiru","branch_name","Main Branch")));

        Map<String,Object> report=service.report(auth,from,to,null,3L,"doctor_revenue");

        assertThat((List<?>)report.get("doctor_revenue")).hasSize(1);
        verify(repo).doctorRevenue(from,to,null,3L);
    }

    @Test void operationsOverviewIncludesRecentlyBookedCancellations() {
        when(users.role(auth)).thenReturn("ADMIN");
        when(repo.totals(from,to,null,null)).thenReturn(totals());
        when(repo.cancelledAppointments(from,to,null,null)).thenReturn(List.of(Map.of("appointment_id",18L)));

        Map<String,Object> report=service.report(auth,from,to,null,null,"summary");

        assertThat(report.get("recent_cancelled_count")).isEqualTo(1);
        assertThat((List<?>)report.get("cancelled_appointments")).hasSize(1);
        assertThat(report.get("date_basis")).isEqualTo("appointment_date");
    }

    @Test void patientsCannotGenerateReports() {
        when(users.role(auth)).thenReturn("PATIENT");
        assertThatThrownBy(() -> service.report(auth,from,to,null,null,"gross_revenue")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repo);
    }

    @Test void expandedFiltersAreAppliedToRevenueQueries() {
        when(users.role(auth)).thenReturn("ADMIN");
        when(repo.totals(any(),any(),eq(7L),eq(3L),eq(2L),eq("Cardiology"))).thenReturn(totals());

        Map<String,Object> report=service.report(auth,from,to,7L,3L,2L," Cardiology ","gross_revenue");

        assertThat(report.get("specialty_id")).isEqualTo(2L);
        assertThat(report.get("department")).isEqualTo("Cardiology");
        assertThat(report).doesNotContainKey("fee_structure");
        verify(repo).totals(from,to,7L,3L,2L,"Cardiology");
    }

    @Test void reportRangeCannotExceedFiveYears() {
        when(users.role(auth)).thenReturn("MANAGER");
        assertThatThrownBy(() -> service.report(auth,LocalDate.of(2020,1,1),LocalDate.of(2025,1,2),null,null,"summary"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("five years");
        verifyNoInteractions(repo);
    }
}
