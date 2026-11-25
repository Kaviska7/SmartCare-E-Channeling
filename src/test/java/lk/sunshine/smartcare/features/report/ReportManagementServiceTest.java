package lk.sunshine.smartcare.features.report;

import lk.sunshine.smartcare.shared.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportManagementServiceTest {
    private final ReportManagementRepository repo=mock(ReportManagementRepository.class);
    private final ReportService reports=mock(ReportService.class);
    private final CurrentUser users=mock(CurrentUser.class);
    private final ReportDeliveryService delivery=mock(ReportDeliveryService.class);
    private final Authentication auth=mock(Authentication.class);
    @TempDir Path storage;
    private ReportManagementService service;

    @BeforeEach void setUp() {
        service=new ReportManagementService(repo,reports,users,delivery,storage.toString());
        when(users.role(auth)).thenReturn("MANAGER");when(users.id(auth)).thenReturn(9L);
        when(repo.doctorExists(any())).thenReturn(true);when(repo.specialtyExists(any())).thenReturn(true);
        when(repo.branchExists(any())).thenReturn(true);when(repo.departmentExists(any())).thenReturn(true);
    }

    private ReportController.TemplateInput template(String name,LocalDate from,LocalDate to) {
        return new ReportController.TemplateInput(name,"gross_revenue","FIXED",from,to,null,null,null,null,"ALL");
    }

    @Test void duplicateTemplateNameIsRejectedPerOwner() {
        when(repo.templateNameCount(9L,"Monthly revenue",null)).thenReturn(1);
        assertThatThrownBy(() -> service.createTemplate(auth,template("Monthly revenue",LocalDate.now().minusMonths(1),LocalDate.now())))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("already exists");
        verify(repo,never()).createTemplate(anyLong(),any(),anyString(),any());
    }

    @Test void fixedTemplateCannotExceedFiveYears() {
        assertThatThrownBy(() -> service.createTemplate(auth,template("Long range",LocalDate.of(2020,1,1),LocalDate.of(2026,1,2))))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("five years");
    }

    @Test void malformedEmailRecipientIsRejected() {
        when(repo.template(9L,4L)).thenReturn(Map.of("id",4L));
        ReportController.ScheduleInput input=new ReportController.ScheduleInput(4L,"MONTHLY","EMAIL",List.of("not-an-email"),"pdf",true,LocalDateTime.now().plusHours(1));
        assertThatThrownBy(() -> service.createSchedule(auth,input)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("invalid");
    }

    @Test void eleventhActiveScheduleIsRejected() {
        when(repo.template(9L,4L)).thenReturn(Map.of("id",4L));when(repo.activeScheduleCount(9L,null)).thenReturn(10);
        ReportController.ScheduleInput input=new ReportController.ScheduleInput(4L,"MONTHLY","EMAIL",List.of("finance@gmail.com"),"pdf",true,LocalDateTime.now().plusHours(1));
        assertThatThrownBy(() -> service.createSchedule(auth,input)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("at most ten");
    }

    @Test void templateWithActiveScheduleCannotBeDeleted() {
        when(repo.template(9L,4L)).thenReturn(Map.of("id",4L));when(repo.activeSchedulesForTemplate(9L,4L)).thenReturn(1);
        assertThatThrownBy(() -> service.deleteTemplate(auth,4L)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Cancel or reassign");
        verify(repo,never()).deleteTemplate(anyLong(),anyLong());
    }

    @Test void futureUtcExecutionIsAcceptedRegardlessOfServerTimezone() {
        when(repo.template(9L,4L)).thenReturn(Map.of("id",4L));
        var input=new ReportController.ScheduleInput(4L,"UTC check","DAILY","EMAIL",List.of("finance@gmail.com"),List.of("pdf"),"UTC",Map.of(),null,null,false,false,LocalDateTime.now(ZoneId.of("UTC")).plusHours(1));
        service.createSchedule(auth,input);
        verify(repo).createSchedule(eq(9L),eq(input),anyString(),anyString(),anyString(),anyString());
    }

    @Test void schedulerDoesNotRunFutureUtcScheduleEarly() {
        when(repo.activeSchedules()).thenReturn(List.of(Map.of("timezone","UTC","next_run_at",LocalDateTime.now(ZoneId.of("UTC")).plusHours(1))));
        service.runDueSchedules();
        verifyNoInteractions(reports,delivery);
        verify(repo,never()).scheduleFailed(anyLong(),any(),any(),anyString());
    }

    @Test void templatePreviewAppliesSelectedFiltersInsteadOfInheritedCriteria() {
        LocalDate from=LocalDate.of(2026,10,1),to=LocalDate.of(2026,10,5);
        Map<String,Object> configuration=Map.of("filters",List.of(
            Map.of("field","doctor","value","2"),Map.of("field","branch","value","3"),
            Map.of("field","specialty","value","4"),Map.of("field","department","value"," Cardiology ")));
        var input=new ReportController.TemplateInput("Preview",null,"PRIVATE","summary","FIXED",from,to,9L,7L,"Old department",8L,configuration);
        service.previewTemplate(auth,input);
        verify(reports).configuredReport("MANAGER",from,to,2L,3L,4L,"Cardiology","summary",configuration);
    }

    @Test void invalidFilterNamesCannotSilentlyProduceUnfilteredPreview() {
        var input=new ReportController.TemplateInput("Preview",null,"PRIVATE","summary","FIXED",
            LocalDate.now().minusDays(1),LocalDate.now(),null,null,null,null,
            Map.of("filters",List.of(Map.of("field","doctor","value","Doctor name"))));
        assertThatThrownBy(()->service.previewTemplate(auth,input)).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Choose a valid doctor");
        verifyNoInteractions(reports);
    }

    @Test void blankFilterKeepsExistingCriteriaAndUnknownSelectionIsRejected() {
        LocalDate from=LocalDate.of(2026,10,1),to=LocalDate.of(2026,10,5);
        Map<String,Object> configuration=Map.of("filters",List.of(Map.of("field","branch","value","")));
        var input=new ReportController.TemplateInput("Preview",null,"PRIVATE","summary","FIXED",from,to,null,null,null,8L,configuration);
        service.previewTemplate(auth,input);
        verify(reports).configuredReport("MANAGER",from,to,null,8L,null,null,"summary",configuration);
        when(repo.branchExists(8L)).thenReturn(false);
        assertThatThrownBy(()->service.previewTemplate(auth,input)).hasMessageContaining("Selected branch does not exist");
    }
}
