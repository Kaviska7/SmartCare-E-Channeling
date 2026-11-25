package lk.sunshine.smartcare.features.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.sunshine.smartcare.shared.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class ReportManagementService {
    private static final int MAX_ACTIVE_SCHEDULES=10,MAX_RECIPIENTS=5;
    private static final Pattern GMAIL=Pattern.compile("^[A-Z0-9._%+-]+@GMAIL\\.COM$",Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE=Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final Set<String> VISIBILITIES=Set.of("PRIVATE","TEAM","PUBLIC");
    private static final Set<String> TYPES=Set.of("summary","doctor_revenue","gross_revenue","refund_cancellation");
    private static final Set<String> FORMATS=Set.of("pdf","xlsx","csv");
    private static final Set<String> FILTERS=Set.of("branch","doctor","specialty","department");
    private static final Set<String> METRICS=Set.of("appointments","cancellations","no_shows","gross_revenue","refunded_revenue","net_revenue","channel_fees","utilization","growth");
    private static final Set<String> COLUMNS=Set.of("doctor","branch","specialty","department","appointments","cancellations","no_shows","gross_revenue","refunded_revenue","net_revenue","channel_fees","utilization","growth");
    private final ReportManagementRepository repo;private final ReportService reports;private final CurrentUser users;
    private final ReportDeliveryService delivery;private final Path storageRoot;private final ObjectMapper json;

    public ReportManagementService(ReportManagementRepository repo,ReportService reports,CurrentUser users,
            ReportDeliveryService delivery,@Value("${smartcare.reports.storage-dir:${java.io.tmpdir}/smartcare-reports}") String storageDir) {
        this.repo=repo;this.reports=reports;this.users=users;this.delivery=delivery;
        this.storageRoot=Path.of(storageDir).toAbsolutePath().normalize();this.json=new ObjectMapper().findAndRegisterModules();
    }

    public record StoredFile(String fileName,String contentType,byte[] bytes) {}

    public List<Map<String,Object>> templates(Authentication auth) {
        String role=viewerRole(auth);long owner=users.id(auth);List<Map<String,Object>> rows=repo.templates(owner,role);
        rows.forEach(row->{inflate(row,"configuration_json","configuration");row.put("editable",number(row,"owner_user_id")==owner);});return rows;
    }
    public Map<String,Object> template(Authentication auth,long id) {
        Map<String,Object> row=accessibleTemplate(users.id(auth),viewerRole(auth),id);inflate(row,"configuration_json","configuration");
        row.put("editable",number(row,"owner_user_id")==users.id(auth));return row;
    }

    @Transactional public long createTemplate(Authentication auth,ReportController.TemplateInput input) {
        long owner=owner(auth);ValidatedTemplate value=validateTemplate(owner,input,null);
        long id=repo.createTemplate(owner,value.input(),value.name(),value.department(),write(value.configuration()));
        syncDefaultSchedule(auth,owner,id,value.configuration());
        users.audit(auth,"CREATE_REPORT_TEMPLATE","report_template",id);return id;
    }
    @Transactional public void updateTemplate(Authentication auth,long id,ReportController.TemplateInput input) {
        long owner=owner(auth);ownedTemplate(owner,id);ValidatedTemplate value=validateTemplate(owner,input,id);
        if(repo.updateTemplate(owner,id,value.input(),value.name(),value.department(),write(value.configuration()))==0)throw notFound("Report template");
        syncDefaultSchedule(auth,owner,id,value.configuration());
        users.audit(auth,"UPDATE_REPORT_TEMPLATE","report_template",id);
    }
    @Transactional public void deleteTemplate(Authentication auth,long id) {
        long owner=owner(auth);ownedTemplate(owner,id);
        if(repo.activeSchedulesForTemplate(owner,id)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Cancel or reassign active schedules before deleting this template");
        if(repo.deleteTemplate(owner,id)==0)throw notFound("Report template");users.audit(auth,"DELETE_REPORT_TEMPLATE","report_template",id);
    }

    public Map<String,Object> preview(Authentication auth,ReportController.GenerateInput input) { requireReportRole(users.role(auth));return generateReport(input,users.role(auth)); }
    public Map<String,Object> previewTemplate(Authentication auth,ReportController.TemplateInput input) {
        String role=users.role(auth);requireReportRole(role);
        Map<String,Object> config=validatedConfiguration(input.configuration()),template=new LinkedHashMap<>();
        if(!Set.of("FIXED","PREVIOUS_PERIOD","PRESET","ROLLING").contains(input.dateMode()))throw bad("Choose a supported date range");
        template.put("date_mode",input.dateMode());template.put("from_date",input.from());template.put("to_date",input.to());
        template.put("doctor_id",input.doctorId());template.put("branch_id",input.branchId());template.put("specialty_id",input.specialtyId());template.put("department",input.department());
        template.put("report_type",type(input.reportType()));template.put("configuration_json",write(config));
        return generate(template,role,"MONTHLY",LocalDate.now(ZoneId.of(String.valueOf(config.getOrDefault("timezone","Asia/Colombo")))));
    }
    @Transactional public Map<String,Object> generate(Authentication auth,ReportController.GenerateInput input) {
        long owner=owner(auth);Map<String,Object> result=generateReport(input,users.role(auth));
        String name=clean(input.name(),"Generated report "+LocalDateTime.now().withSecond(0).withNano(0));
        long id=repo.addHistory(owner,null,null,name,"MANUAL",write(criteria(input)),write(result));
        users.audit(auth,"GENERATE_REPORT","generated_report",id);Map<String,Object> response=new LinkedHashMap<>(result);response.put("id",id);response.put("report_name",name);return response;
    }
    public List<Map<String,Object>> history(Authentication auth) { long owner=owner(auth);List<Map<String,Object>> rows=repo.history(owner);rows.forEach(row->inflate(row,"criteria_json","criteria"));return rows; }
    public Map<String,Object> historyItem(Authentication auth,long id) { Map<String,Object> row=ownedHistory(owner(auth),id);inflate(row,"criteria_json","criteria");inflate(row,"result_json","result");return row; }
    public StoredFile exportHistory(Authentication auth,long id,String format) throws IOException {
        long owner=owner(auth);Map<String,Object> row=ownedHistory(owner,id);Map<String,Object> result=readMap(row.get("result_json"));
        ReportExporter exporter=reports.exporter(format);String base=safeName(row.get("report_name").toString());String fileName=base+"-"+System.currentTimeMillis()+"."+format;
        Files.createDirectories(storageRoot);Path path=safePath(storageRoot.resolve(fileName).toString());byte[] bytes=reports.export(exporter,result);Files.write(path,bytes);
        repo.addFile(owner,nullableNumber(row.get("template_id")),nullableNumber(row.get("schedule_id")),id,result.get("report_type").toString(),format,fileName,path.toString(),LocalDateTime.now().plusDays(7));
        return new StoredFile(fileName,exporter.contentType(),bytes);
    }
    @Transactional public void deleteHistory(Authentication auth,long id) throws IOException {
        long owner=owner(auth);ownedHistory(owner,id);for(Map<String,Object> file:repo.historyFiles(owner,id))Files.deleteIfExists(safePath(file.get("storage_path").toString()));
        repo.deleteHistory(owner,id);users.audit(auth,"DELETE_GENERATED_REPORT","generated_report",id);
    }

    public List<Map<String,Object>> schedules(Authentication auth) { long owner=owner(auth);List<Map<String,Object>> rows=repo.schedules(owner);rows.forEach(row->{row.put("recipient_list",splitRecipients(row.get("recipients").toString()));row.put("formats",readList(row.get("formats_json")));inflate(row,"recurrence_json","recurrence");row.put("next_run_at",toDateTime(row.get("next_run_at")).toString());});return rows; }
    @Transactional public long createSchedule(Authentication auth,ReportController.ScheduleInput input) {
        long owner=owner(auth);ValidatedSchedule value=validateSchedule(owner,input,null);long id=repo.createSchedule(owner,value.input(),value.name(),value.recipients(),write(value.formats()),write(value.recurrence()));
        users.audit(auth,"CREATE_REPORT_SCHEDULE","report_schedule",id);return id;
    }
    @Transactional public void updateSchedule(Authentication auth,long id,ReportController.ScheduleInput input) {
        long owner=owner(auth);ownedSchedule(owner,id);ValidatedSchedule value=validateSchedule(owner,input,id);
        if(repo.updateSchedule(owner,id,value.input(),value.name(),value.recipients(),write(value.formats()),write(value.recurrence()))==0)throw notFound("Report schedule");
        users.audit(auth,"UPDATE_REPORT_SCHEDULE","report_schedule",id);
    }
    @Transactional public void deleteSchedule(Authentication auth,long id) { long owner=owner(auth);ownedSchedule(owner,id);if(repo.deleteSchedule(owner,id)==0)throw notFound("Report schedule");users.audit(auth,"DELETE_REPORT_SCHEDULE","report_schedule",id); }
    public void runSchedule(Authentication auth,long id) throws IOException { long owner=owner(auth);Map<String,Object> schedule=ownedSchedule(owner,id);executeSchedule(schedule,users.role(auth),true);users.audit(auth,"RUN_REPORT_SCHEDULE","report_schedule",id); }

    public List<Map<String,Object>> files(Authentication auth) { return repo.files(owner(auth)); }
    public long generateSaved(Authentication auth,long templateId,String format) throws IOException {
        long owner=owner(auth);Map<String,Object> template=ownedTemplate(owner,templateId);Map<String,Object> report=generate(template,users.role(auth),"MONTHLY",LocalDate.now());
        long historyId=repo.addHistory(owner,templateId,null,template.get("name").toString(),"TEMPLATE",write(template),write(report));
        long fileId=createFile(owner,templateId,null,historyId,template,report,format);users.audit(auth,"GENERATE_SAVED_REPORT","generated_report",historyId);return fileId;
    }
    public StoredFile file(Authentication auth,long id) throws IOException { long owner=owner(auth);Map<String,Object> row=ownedFile(owner,id);if(!toDateTime(row.get("expires_at")).isAfter(LocalDateTime.now())){purgeFile(auth,id);throw new ResponseStatusException(HttpStatus.GONE,"Generated report file has expired");}Path path=safePath(row.get("storage_path").toString());if(!Files.exists(path))throw notFound("Generated report file");String format=row.get("export_format").toString();return new StoredFile(row.get("file_name").toString(),reports.exporter(format).contentType(),Files.readAllBytes(path)); }
    @Transactional public void purgeFile(Authentication auth,long id) throws IOException { long owner=owner(auth);Map<String,Object> row=ownedFile(owner,id);Files.deleteIfExists(safePath(row.get("storage_path").toString()));repo.deleteFile(owner,id);users.audit(auth,"PURGE_REPORT_FILE","generated_report_file",id); }

    @Scheduled(fixedDelay=60000) public void runDueSchedules() {
        for(Map<String,Object> schedule:repo.activeSchedules()) {
            LocalDateTime now=LocalDateTime.now(scheduleZone(schedule));
            if(toDateTime(schedule.get("next_run_at")).isAfter(now))continue;
            try{executeSchedule(schedule,schedule.get("owner_role").toString(),false);}
            catch(Exception error){long id=number(schedule,"schedule_id");String message=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();repo.scheduleFailed(id,LocalDateTime.now(),nextRun(toDateTime(schedule.get("next_run_at")),schedule.get("frequency").toString(),now),message.substring(0,Math.min(500,message.length())));}
        }
    }
    @Scheduled(cron="0 15 1 * * *") public void purgeExpiredFiles() { for(Map<String,Object> row:repo.expiredFiles(LocalDateTime.now()))try{Files.deleteIfExists(safePath(row.get("storage_path").toString()));repo.deleteFile(number(row,"owner_user_id"),number(row,"id"));}catch(IOException ignored){} }

    private void executeSchedule(Map<String,Object> schedule,String role,boolean manual) throws IOException {
        if(schedule.containsKey("owner_active")&&!Boolean.TRUE.equals(schedule.get("owner_active")))throw new IllegalStateException("Schedule owner is no longer authorised");
        long scheduleId=number(schedule,schedule.containsKey("schedule_id")?"schedule_id":"id"),ownerId=number(schedule,"owner_user_id");
        Map<String,Object> template=schedule.containsKey("report_type")?schedule:ownedTemplate(ownerId,number(schedule,"template_id"));
        Map<String,Object> report=generate(template,role,schedule.get("frequency").toString(),LocalDate.now(scheduleZone(schedule)));String name=String.valueOf(schedule.getOrDefault("schedule_name",template.get("name")));
        long historyId=repo.addHistory(ownerId,number(schedule,"template_id"),scheduleId,name,"SCHEDULE",write(template),write(report));
        List<String> formats=schedule.get("formats_json")==null?List.of(schedule.get("export_format").toString()):readList(schedule.get("formats_json"));
        for(String format:formats){long fileId=createFile(ownerId,number(schedule,"template_id"),scheduleId,historyId,template,report,format);Map<String,Object> stored=repo.file(ownerId,fileId);delivery.deliver(schedule.get("delivery_channel").toString(),splitRecipients(schedule.get("recipients").toString()),safePath(stored.get("storage_path").toString()),name);}
        if(!manual){LocalDateTime now=LocalDateTime.now(scheduleZone(schedule));repo.scheduleSucceeded(scheduleId,LocalDateTime.now(),nextRun(toDateTime(schedule.get("next_run_at")),schedule.get("frequency").toString(),now));}
    }

    private Map<String,Object> generateReport(ReportController.GenerateInput input,String role) { validateCriteria(input.from(),input.to(),input.doctorId(),input.branchId(),input.specialtyId(),input.department(),input.configuration());Map<String,Object> template=new LinkedHashMap<>();template.put("date_mode","FIXED");template.put("from_date",input.from());template.put("to_date",input.to());template.put("doctor_id",input.doctorId());template.put("branch_id",input.branchId());template.put("specialty_id",input.specialtyId());template.put("department",clean(input.department(),null));template.put("report_type",type(input.type()));template.put("configuration_json",write(validatedConfiguration(input.configuration())));return generate(template,role,"MONTHLY",LocalDate.now()); }
    private Map<String,Object> criteria(ReportController.GenerateInput input) { Map<String,Object> value=new LinkedHashMap<>();value.put("name",input.name());value.put("from",input.from());value.put("to",input.to());value.put("doctorId",input.doctorId());value.put("branchId",input.branchId());value.put("specialtyId",input.specialtyId());value.put("department",input.department());value.put("type",type(input.type()));value.put("configuration",input.configuration()==null?Map.of():input.configuration());return value; }
    private Map<String,Object> generate(Map<String,Object> template,String role,String frequency,LocalDate today) {
        LocalDate[] dates=resolveDates(template,frequency,today);Map<String,Object> config=readMap(template.get("configuration_json"));
        Long doctor=nullableNumber(template.get("doctor_id")),branch=nullableNumber(template.get("branch_id")),specialty=nullableNumber(template.get("specialty_id"));String department=stringOrNull(template.get("department"));
        Object filterValue=config.get("filters");
        if(filterValue instanceof List<?> filters)for(Object item:filters)if(item instanceof Map<?,?> filter) {
            String field=String.valueOf(filter.get("field")).toLowerCase(Locale.ROOT);
            String value=stringOrNull(filter.get("value"));
            if(value==null)continue;
            // Explicit template selections take precedence over criteria inherited from Generate Report.
            switch(field) {
                case "doctor" -> doctor=filterId(field,value);
                case "branch" -> branch=filterId(field,value);
                case "specialty" -> specialty=filterId(field,value);
                case "department" -> department=value;
                default -> throw bad("Unsupported report filter");
            }
        }
        validateCriteria(dates[0],dates[1],doctor,branch,specialty,department,config);return reports.configuredReport(role,dates[0],dates[1],doctor,branch,specialty,department,type(String.valueOf(template.get("report_type"))),config);
    }

    private LocalDate[] resolveDates(Map<String,Object> template,String frequency,LocalDate today) {
        if("FIXED".equals(template.get("date_mode")))return new LocalDate[]{toDate(template.get("from_date")),toDate(template.get("to_date"))};
        Map<String,Object> config=readMap(template.get("configuration_json"));Object ruleObject=config.get("dateRange");if(ruleObject instanceof Map<?,?> raw){Map<String,Object> rule=new LinkedHashMap<>();raw.forEach((k,v)->rule.put(String.valueOf(k),v));if("ROLLING".equals(template.get("date_mode"))){int amount=Math.max(1,Math.min(1825,Integer.parseInt(String.valueOf(rule.getOrDefault("rollingAmount",30)))));boolean months="MONTHS".equals(rule.get("rollingUnit")),next="NEXT".equals(rule.get("direction"));LocalDate edge=months?(next?today.plusMonths(amount):today.minusMonths(amount)):(next?today.plusDays(amount):today.minusDays(amount));return next?new LocalDate[]{today,edge}:new LocalDate[]{edge,today};}String preset=String.valueOf(rule.getOrDefault("preset","LAST_MONTH"));return switch(preset){case "TODAY"->new LocalDate[]{today,today};case "YESTERDAY"->new LocalDate[]{today.minusDays(1),today.minusDays(1)};case "THIS_WEEK"->{LocalDate start=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));yield new LocalDate[]{start,today};}case "QUARTER_TO_DATE"->{int month=((today.getMonthValue()-1)/3)*3+1;yield new LocalDate[]{LocalDate.of(today.getYear(),month,1),today};}case "YEAR_TO_DATE"->new LocalDate[]{today.withDayOfYear(1),today};default->{LocalDate start=today.minusMonths(1).withDayOfMonth(1);yield new LocalDate[]{start,start.with(TemporalAdjusters.lastDayOfMonth())};}};}
        return previousPeriod(frequency,today);
    }
    private LocalDate[] previousPeriod(String frequency,LocalDate today) { return switch(frequency){case "DAILY"->new LocalDate[]{today.minusDays(1),today.minusDays(1)};case "WEEKLY"->{LocalDate start=today.minusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));yield new LocalDate[]{start,start.plusDays(6)};}default->{LocalDate start=today.minusMonths(1).withDayOfMonth(1);yield new LocalDate[]{start,start.with(TemporalAdjusters.lastDayOfMonth())};}}; }

    private ValidatedTemplate validateTemplate(long owner,ReportController.TemplateInput p,Long exceptId) {
        String name=clean(p.name(),null);if(name==null)throw bad("Template name is required");if(repo.templateNameCount(owner,name,exceptId)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Template name already exists in your account");
        String visibility=p.visibility()==null?"PRIVATE":p.visibility().toUpperCase();if(!VISIBILITIES.contains(visibility))throw bad("Choose Private, Team / Workspace, or Public visibility");
        ReportController.TemplateInput normalized=new ReportController.TemplateInput(name,clean(p.description(),null),visibility,type(p.reportType()),p.dateMode(),p.from(),p.to(),p.doctorId(),p.specialtyId(),clean(p.department(),null),p.branchId(),p.configuration());
        if(!Set.of("FIXED","PREVIOUS_PERIOD","PRESET","ROLLING").contains(normalized.dateMode()))throw bad("Choose a supported date range");if("FIXED".equals(normalized.dateMode()))validateRange(p.from(),p.to());
        validateCriteria(p.from(),p.to(),p.doctorId(),p.branchId(),p.specialtyId(),p.department(),p.configuration());return new ValidatedTemplate(normalized,name,clean(p.department(),null),validatedConfiguration(p.configuration()));
    }
    private void validateCriteria(LocalDate from,LocalDate to,Long doctorId,Long branchId,Long specialtyId,String department,Map<String,Object> configuration) {
        if(from!=null||to!=null)validateRange(from,to);if(!repo.doctorExists(doctorId))throw bad("Selected doctor does not exist");if(!repo.specialtyExists(specialtyId))throw bad("Selected specialty does not exist");if(!repo.branchExists(branchId))throw bad("Selected branch does not exist");String cleanDepartment=clean(department,null);if(!repo.departmentExists(cleanDepartment))throw bad("Selected department does not exist");validatedConfiguration(configuration);
    }
    private Map<String,Object> validatedConfiguration(Map<String,Object> source) {
        Map<String,Object> config=source==null?new LinkedHashMap<>():new LinkedHashMap<>(source);checkList(config,"metrics",METRICS);checkList(config,"columns",COLUMNS);
        if(!Set.of("doctor","branch","specialty","department").contains(String.valueOf(config.getOrDefault("groupBy","doctor"))))throw bad("Choose a supported grouping");
        if(!METRICS.contains(String.valueOf(config.getOrDefault("sortField","net_revenue"))))throw bad("Choose a supported sort field");
        if(!Set.of("ASC","DESC").contains(String.valueOf(config.getOrDefault("sortDirection","DESC"))))throw bad("Choose a supported sort direction");
        if(!Set.of("BAR","LINE","PIE").contains(String.valueOf(config.getOrDefault("chartType","BAR"))))throw bad("Choose a supported chart type");
        try{ZoneId.of(String.valueOf(config.getOrDefault("timezone","Asia/Colombo")));}catch(Exception error){throw bad("Choose a valid timezone");}
        if(config.get("dateRange") instanceof Map<?,?> rule && rule.containsKey("rollingAmount")) {
            int amount;try{amount=Integer.parseInt(String.valueOf(rule.get("rollingAmount")));}catch(Exception error){throw bad("Rolling amount must be a whole number");}
            if(amount<1 || amount>("MONTHS".equals(rule.get("rollingUnit"))?60:1825))throw bad("Rolling range must be between one day and five years");
        }
        Object filters=config.get("filters");
        if(filters instanceof List<?> list)for(Object item:list)if(item instanceof Map<?,?> map) {
            String field=String.valueOf(map.get("field")).toLowerCase(Locale.ROOT),value=stringOrNull(map.get("value"));
            if(!FILTERS.contains(field))throw bad("Unsupported report filter");
            if(value==null)continue;
            if(!"department".equals(field))filterId(field,value);
        }
        String layout=String.valueOf(config.getOrDefault("displayType","SUMMARY_TABLE"));if(!Set.of("TABLE","CHART","SUMMARY_TABLE").contains(layout))throw bad("Choose a supported display type");return config;
    }
    private Long filterId(String field,String value) {
        try { long id=Long.parseLong(value);if(id>0)return id; }
        catch(NumberFormatException ignored) {}
        throw bad("Choose a valid "+field+" from the filter list");
    }
    private void checkList(Map<String,Object> config,String key,Set<String> allowed) { Object value=config.get(key);if(value instanceof List<?> list&&list.stream().map(String::valueOf).map(String::toLowerCase).anyMatch(item->!allowed.contains(item)))throw bad("Unsupported report "+key); }

    private ValidatedSchedule validateSchedule(long owner,ReportController.ScheduleInput p,Long exceptId) {
        ownedTemplate(owner,p.templateId());if(!Set.of("DAILY","WEEKLY","MONTHLY","QUARTERLY").contains(p.frequency()))throw bad("Choose daily, weekly, monthly, or quarterly frequency");if(!Set.of("EMAIL","SMS").contains(p.deliveryChannel()))throw bad("Choose email or SMS delivery");
        List<String> formats=p.formats()==null?List.of():p.formats().stream().map(String::toLowerCase).distinct().toList();if(formats.isEmpty()||formats.stream().anyMatch(value->!FORMATS.contains(value)))throw bad("Choose PDF, Excel, or CSV");
        ZoneId zone;try{zone=ZoneId.of(p.timezone());}catch(Exception error){throw bad("Choose a valid timezone");}
        if(p.nextRunAt().isBefore(LocalDateTime.now(zone).minusMinutes(1)))throw bad("Next execution time cannot be in the past");if(p.active()&&repo.activeScheduleCount(owner,exceptId)>=MAX_ACTIVE_SCHEDULES)throw new ResponseStatusException(HttpStatus.CONFLICT,"Each account may have at most ten active report schedules");
        List<String> recipients=p.recipients().stream().map(value->value.trim().toLowerCase()).filter(value->!value.isEmpty()).distinct().toList();if(recipients.isEmpty())throw bad("Add at least one recipient");if(recipients.size()>MAX_RECIPIENTS)throw bad("A schedule may have at most five recipients");Pattern pattern="EMAIL".equals(p.deliveryChannel())?GMAIL:PHONE;if(recipients.stream().anyMatch(value->!pattern.matcher(value).matches()))throw bad("One or more "+p.deliveryChannel().toLowerCase(Locale.ROOT)+" recipients are invalid");
        try{ZoneId.of(p.timezone());}catch(Exception error){throw bad("Choose a valid timezone");}String name=clean(p.name(),"Delivery schedule");return new ValidatedSchedule(p,name,String.join(";",recipients),formats,p.recurrence()==null?Map.of():p.recurrence());
    }

    private void syncDefaultSchedule(Authentication auth,long owner,long templateId,Map<String,Object> config) {
        Map<String,Object> existing=repo.defaultSchedule(owner,templateId);boolean enabled=Boolean.TRUE.equals(config.get("automationEnabled"));
        if(!enabled){if(existing!=null)repo.setScheduleActive(owner,number(existing,"id"),false);return;}
        String email=auth.getName().trim().toLowerCase();if(!GMAIL.matcher(email).matches())throw bad("Automated template delivery requires your account to use a valid @gmail.com address");
        List<String> formats=config.get("exportFormats") instanceof List<?> values?values.stream().map(String::valueOf).map(String::toLowerCase).filter(FORMATS::contains).distinct().toList():List.of("pdf");
        if(formats.isEmpty())formats=List.of("pdf");String timezone=String.valueOf(config.getOrDefault("timezone","Asia/Colombo"));
        ZonedDateTime now=ZonedDateTime.now(ZoneId.of(timezone)),next=now.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).withHour(9).withMinute(0).withSecond(0).withNano(0);if(!next.isAfter(now))next=next.plusWeeks(1);
        ReportController.ScheduleInput input=new ReportController.ScheduleInput(templateId,"Default delivery", "WEEKLY","EMAIL",List.of(email),formats,timezone,Map.of("day","MONDAY","time","09:00"),"SmartCare report",null,true,true,next.toLocalDateTime());
        ValidatedSchedule value=validateSchedule(owner,input,existing==null?null:number(existing,"id"));
        if(existing==null)repo.createSchedule(owner,input,value.name(),value.recipients(),write(value.formats()),write(value.recurrence()));
        else repo.updateSchedule(owner,number(existing,"id"),input,value.name(),value.recipients(),write(value.formats()),write(value.recurrence()));
    }

    private long createFile(long ownerId,Long templateId,Long scheduleId,Long historyId,Map<String,Object> template,Map<String,Object> report,String format) throws IOException { ReportExporter exporter=reports.exporter(format);Files.createDirectories(storageRoot);String safe=safeName(String.valueOf(template.getOrDefault("template_name",template.getOrDefault("name","report"))));String fileName=safe+"-"+System.currentTimeMillis()+"."+format;Path path=safePath(storageRoot.resolve(fileName).toString());Files.write(path,reports.export(exporter,report));return repo.addFile(ownerId,templateId,scheduleId,historyId,report.get("report_type").toString(),format,fileName,path.toString(),LocalDateTime.now().plusDays(7)); }
    private LocalDateTime nextRun(LocalDateTime scheduled,String frequency,LocalDateTime now){LocalDateTime next=scheduled;do next=switch(frequency){case "DAILY"->next.plusDays(1);case "WEEKLY"->next.plusWeeks(1);case "QUARTERLY"->next.plusMonths(3);default->next.plusMonths(1);};while(!next.isAfter(now));return next;}
    private void validateRange(LocalDate from,LocalDate to){if(from==null||to==null||from.isAfter(to))throw bad("Start date must be on or before end date");if(from.plusYears(5).isBefore(to))throw bad("Report date range cannot exceed five years");}
    private String type(String value){String type=value==null||value.isBlank()?"summary":value;if(!TYPES.contains(type))throw bad("Choose a report type");return type;}
    private long owner(Authentication auth){String role=users.role(auth);requireReportRole(role);return users.id(auth);}private void requireReportRole(String role){if(!Set.of("ADMIN","MANAGER").contains(role))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Administrator or operations manager required");}
    private String viewerRole(Authentication auth){String role=users.role(auth);if(!Set.of("ADMIN","MANAGER","DOCTOR","RECEPTION").contains(role))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Staff access required");return role;}
    private Map<String,Object> accessibleTemplate(long owner,String role,long id){try{return repo.template(owner,role,id);}catch(EmptyResultDataAccessException error){throw notFound("Report template");}}
    private Map<String,Object> ownedTemplate(long owner,long id){try{return repo.template(owner,id);}catch(EmptyResultDataAccessException error){throw notFound("Report template");}}
    private Map<String,Object> ownedSchedule(long owner,long id){try{return repo.schedule(owner,id);}catch(EmptyResultDataAccessException error){throw notFound("Report schedule");}}
    private Map<String,Object> ownedHistory(long owner,long id){try{return repo.historyItem(owner,id);}catch(EmptyResultDataAccessException error){throw notFound("Generated report");}}
    private Map<String,Object> ownedFile(long owner,long id){try{return repo.file(owner,id);}catch(EmptyResultDataAccessException error){throw notFound("Generated report file");}}
    private void inflate(Map<String,Object> row,String source,String target){Object raw=row.get(source);if(raw!=null)row.put(target,readMap(raw));}
    private Map<String,Object> readMap(Object value){if(value==null||value.toString().isBlank())return new LinkedHashMap<>();try{return json.readValue(value.toString(),new TypeReference<>(){});}catch(IOException error){return new LinkedHashMap<>();}}
    private List<String> readList(Object value){if(value==null)return List.of();try{return json.readValue(value.toString(),new TypeReference<>(){});}catch(IOException error){return List.of();}}
    private String write(Object value){try{return json.writeValueAsString(value==null?Map.of():value);}catch(IOException error){throw new IllegalStateException("Could not store report configuration",error);}}
    private List<String> splitRecipients(String recipients){return Arrays.stream(recipients.split(";")).filter(value->!value.isBlank()).toList();}
    private Path safePath(String value){Path path=Path.of(value).toAbsolutePath().normalize();if(!path.startsWith(storageRoot))throw bad("Invalid report file path");return path;}
    private String safeName(String value){String safe=value.replaceAll("[^A-Za-z0-9._-]+","-").replaceAll("^-|-$","");return safe.isBlank()?"report":safe;}
    private String clean(String value,String fallback){if(value==null||value.trim().isEmpty())return fallback;return value.trim();}
    private String stringOrNull(Object value){return value==null||value.toString().trim().isEmpty()?null:value.toString().trim();}
    private Long nullableNumber(Object value){return value instanceof Number number?number.longValue():null;}private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private LocalDate toDate(Object value){if(value instanceof java.sql.Date date)return date.toLocalDate();return (LocalDate)value;}private LocalDateTime toDateTime(Object value){if(value instanceof java.sql.Timestamp time)return time.toLocalDateTime();return (LocalDateTime)value;}
    private ZoneId scheduleZone(Map<String,Object> schedule){return ZoneId.of(String.valueOf(schedule.getOrDefault("timezone","Asia/Colombo")));}
    private ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}private ResponseStatusException notFound(String entity){return new ResponseStatusException(HttpStatus.NOT_FOUND,entity+" not found");}
    private record ValidatedTemplate(ReportController.TemplateInput input,String name,String department,Map<String,Object> configuration){}
    private record ValidatedSchedule(ReportController.ScheduleInput input,String name,String recipients,List<String> formats,Map<String,Object> recurrence){}
}

