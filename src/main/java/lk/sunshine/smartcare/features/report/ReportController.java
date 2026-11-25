package lk.sunshine.smartcare.features.report;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService service;
    private final ReportManagementService management;
    public ReportController(ReportService service,ReportManagementService management) { this.service=service;this.management=management; }

    public record TemplateInput(@NotBlank @Size(max=120) String name,@Size(max=500) String description,String visibility,
            @NotBlank String reportType,@NotBlank String dateMode,LocalDate from,LocalDate to,@Positive Long doctorId,
            @Positive Long specialtyId,@Size(max=100) String department,@Positive Long branchId,Map<String,Object> configuration) {
        public TemplateInput(String name,String reportType,String dateMode,LocalDate from,LocalDate to,Long doctorId,
                Long specialtyId,String department,Long branchId,String ignoredFeeStructure) {
            this(name,null,"PRIVATE",reportType,dateMode,from,to,doctorId,specialtyId,department,branchId,Map.of());
        }
    }
    public record ScheduleInput(@Positive long templateId,@Size(max=120) String name,@NotBlank String frequency,
            @NotBlank String deliveryChannel,@NotEmpty List<@NotBlank String> recipients,List<String> formats,
            @NotBlank String timezone,Map<String,Object> recurrence,@Size(max=200) String emailSubject,
            @Size(max=1000) String emailMessage,boolean active,boolean templateDefault,@NotNull LocalDateTime nextRunAt) {
        public ScheduleInput(long templateId,String frequency,String deliveryChannel,List<String> recipients,
                String exportFormat,boolean active,LocalDateTime nextRunAt) {
            this(templateId,null,frequency,deliveryChannel,recipients,List.of(exportFormat),"Asia/Colombo",Map.of(),null,null,active,false,nextRunAt);
        }
    }
    public record GenerateInput(@Size(max=160) String name,@NotNull LocalDate from,@NotNull LocalDate to,
            Long doctorId,Long branchId,Long specialtyId,@Size(max=100) String department,
            String type,Map<String,Object> configuration) {}

    @GetMapping public Map<String,Object> report(Authentication auth,@RequestParam LocalDate from,@RequestParam LocalDate to,
            @RequestParam(required=false) Long doctorId,@RequestParam(required=false) Long branchId,
            @RequestParam(required=false) Long specialtyId,@RequestParam(required=false) String department,
            @RequestParam(defaultValue="summary") String type) {
        return service.report(auth,from,to,doctorId,branchId,specialtyId,department,type);
    }
    @GetMapping("/export/{format}") public ResponseEntity<byte[]> export(Authentication auth,@PathVariable String format,
            @RequestParam LocalDate from,@RequestParam LocalDate to,@RequestParam(required=false) Long doctorId,
            @RequestParam(required=false) Long branchId,@RequestParam(required=false) Long specialtyId,
            @RequestParam(required=false) String department,@RequestParam(defaultValue="summary") String type) throws IOException {
        ReportExporter exporter=service.exporter(format);
        return attachment(exporter,service.export(exporter,service.report(auth,from,to,doctorId,branchId,specialtyId,department,type)),"smartcare-report."+format);
    }

    @PostMapping("/preview") public Map<String,Object> preview(Authentication auth,@Valid @RequestBody GenerateInput input) { return management.preview(auth,input); }
    @PostMapping("/templates/preview") public Map<String,Object> previewTemplate(Authentication auth,@Valid @RequestBody TemplateInput input) { return management.previewTemplate(auth,input); }
    @PostMapping("/generate") public Map<String,Object> generate(Authentication auth,@Valid @RequestBody GenerateInput input) { return management.generate(auth,input); }
    @GetMapping("/history") public List<Map<String,Object>> history(Authentication auth) { return management.history(auth); }
    @GetMapping("/history/{id}") public Map<String,Object> historyItem(Authentication auth,@PathVariable long id) { return management.historyItem(auth,id); }
    @PostMapping("/history/{id}/export/{format}") public ResponseEntity<byte[]> exportHistory(Authentication auth,@PathVariable long id,@PathVariable String format) throws IOException {
        ReportManagementService.StoredFile file=management.exportHistory(auth,id,format);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType())).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+file.fileName()+"\"").body(file.bytes());
    }
    @DeleteMapping("/history/{id}") public Map<String,String> deleteHistory(Authentication auth,@PathVariable long id) throws IOException { management.deleteHistory(auth,id);return Map.of("message","Generated report deleted"); }

    @GetMapping("/templates") public List<Map<String,Object>> templates(Authentication auth) { return management.templates(auth); }
    @GetMapping("/templates/{id}") public Map<String,Object> template(Authentication auth,@PathVariable long id) { return management.template(auth,id); }
    @PostMapping("/templates") public Map<String,Object> createTemplate(Authentication auth,@Valid @RequestBody TemplateInput input) { return Map.of("id",management.createTemplate(auth,input)); }
    @PutMapping("/templates/{id}") public Map<String,String> updateTemplate(Authentication auth,@PathVariable long id,@Valid @RequestBody TemplateInput input) { management.updateTemplate(auth,id,input);return Map.of("message","Report template saved"); }
    @DeleteMapping("/templates/{id}") public Map<String,String> deleteTemplate(Authentication auth,@PathVariable long id) { management.deleteTemplate(auth,id);return Map.of("message","Report template deleted"); }
    @PostMapping("/templates/{id}/generate/{format}") public Map<String,Object> generateSaved(Authentication auth,@PathVariable long id,@PathVariable String format) throws IOException { return Map.of("id",management.generateSaved(auth,id,format)); }

    @GetMapping("/schedules") public List<Map<String,Object>> schedules(Authentication auth) { return management.schedules(auth); }
    @PostMapping("/schedules") public Map<String,Object> createSchedule(Authentication auth,@Valid @RequestBody ScheduleInput input) { return Map.of("id",management.createSchedule(auth,input)); }
    @PutMapping("/schedules/{id}") public Map<String,String> updateSchedule(Authentication auth,@PathVariable long id,@Valid @RequestBody ScheduleInput input) { management.updateSchedule(auth,id,input);return Map.of("message","Delivery schedule saved"); }
    @PostMapping("/schedules/{id}/run") public Map<String,String> runSchedule(Authentication auth,@PathVariable long id) throws IOException { management.runSchedule(auth,id);return Map.of("message","Report schedule ran successfully"); }
    @DeleteMapping("/schedules/{id}") public Map<String,String> deleteSchedule(Authentication auth,@PathVariable long id) throws IOException { management.deleteSchedule(auth,id);return Map.of("message","Delivery schedule deleted"); }

    @GetMapping("/files") public List<Map<String,Object>> files(Authentication auth) { return management.files(auth); }
    @GetMapping("/files/{id}") public ResponseEntity<byte[]> downloadFile(Authentication auth,@PathVariable long id) throws IOException {
        ReportManagementService.StoredFile file=management.file(auth,id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType())).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+file.fileName()+"\"").body(file.bytes());
    }
    @DeleteMapping("/files/{id}") public Map<String,String> purgeFile(Authentication auth,@PathVariable long id) throws IOException { management.purgeFile(auth,id);return Map.of("message","Generated report file purged"); }

    private ResponseEntity<byte[]> attachment(ReportExporter exporter,byte[] bytes,String name) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(exporter.contentType())).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+name+"\"").body(bytes);
    }
}
