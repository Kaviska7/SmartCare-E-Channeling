package lk.sunshine.smartcare.features.doctor;

import lk.sunshine.smartcare.shared.CurrentUser;
import lk.sunshine.smartcare.shared.EmailPolicy;
import lk.sunshine.smartcare.features.notification.ScheduleEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class DoctorService {
    private static final Pattern REGISTRATION_ID=Pattern.compile("[A-Z0-9][A-Z0-9/-]{2,49}");
    private final DoctorRepository repo; private final CurrentUser users; private final PasswordEncoder encoder; private final ApplicationEventPublisher events;
    public DoctorService(DoctorRepository repo,CurrentUser users,PasswordEncoder encoder,ApplicationEventPublisher events) { this.repo=repo;this.users=users;this.encoder=encoder;this.events=events; }
    public List<Map<String,Object>> doctors(String specialty,Long branchId) { return enrich(repo.doctors(specialty==null?"":specialty.trim(),branchId)); }
    public List<Map<String,Object>> allForAdmin(Authentication auth,String specialty) { requireAdmin(auth);return enrich(repo.allForAdmin(specialty==null?"":specialty.trim())); }
    public List<Map<String,Object>> specialties() { return repo.specialties(); }
    @Transactional public long createSpecialty(Authentication auth,String name) {
        requireAdmin(auth);
        String clean=name.trim();
        if(repo.specialtyCount(clean)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Specialty already exists");
        long id=repo.createSpecialty(clean);users.audit(auth,"CREATE_SPECIALTY","specialty",id);return id;
    }
    @Transactional public void deleteSpecialty(Authentication auth,long id) {
        requireAdmin(auth);
        if(repo.specialtyExists(id)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Specialty not found");
        if(repo.deleteSpecialty(id)==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Specialty is assigned to a doctor");
        users.audit(auth,"DELETE_SPECIALTY","specialty",id);
    }
    public List<Map<String,Object>> slots(long id,Long branchId) { return repo.slots(id,branchId); }
    public Map<String,Object> mine(Authentication auth) { if(!"DOCTOR".equals(users.role(auth)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Doctor account required");return enrichOne(repo.mine(users.id(auth))); }
    public List<Map<String,Object>> schedules(Authentication auth,long id) { requireOwnerOrAdmin(auth,id);return repo.schedules(id); }
    public List<Map<String,Object>> leaves(Authentication auth,long id) { requireOwnerOrAdmin(auth,id);return repo.leaves(id); }
    @Transactional public long addLeave(Authentication auth,long doctorId,LocalDate date,String reason) {
        requireOwnerOrAdmin(auth,doctorId);
        if(date.isBefore(LocalDate.now()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Leave date must be today or later");
        if(repo.leaveCount(doctorId,date)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Leave already recorded for this date");
        repo.retireOpenSlotsOnDate(doctorId,date);
        if(repo.bookedOnDate(doctorId,date)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Existing appointments must be handled before recording leave");
        long id=repo.addLeave(doctorId,date,reason==null?null:reason.trim());
        users.audit(auth,"ADD_DOCTOR_LEAVE","doctor_leave",id);
        events.publishEvent(new ScheduleEvent(doctorId,"Leave added for "+date+". Open times on that date are unavailable."));
        return id;
    }
    @Transactional public void deleteLeave(Authentication auth,long doctorId,long leaveId) {
        requireOwnerOrAdmin(auth,doctorId);
        if(repo.deleteLeave(doctorId,leaveId)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Current or future leave not found");
        for(Map<String,Object> row:repo.activeSchedules())if(((Number)row.get("doctor_id")).longValue()==doctorId)
            generate(doctorId,((Number)row.get("branch_id")).longValue(),localDate(row.get("schedule_date")),localTime(row.get("start_time")),localTime(row.get("end_time")),((Number)row.get("slot_minutes")).intValue(),((Number)row.get("capacity")).intValue());
        users.audit(auth,"REMOVE_DOCTOR_LEAVE","doctor_leave",leaveId);
        events.publishEvent(new ScheduleEvent(doctorId,"Leave removed. Open times on your calendar have been restored."));
    }
    @Transactional public long createDoctor(Authentication auth,DoctorController.DoctorInput p) {
        requireAdmin(auth);
        if(p.userId()==null || !"DOCTOR".equals(repo.userRole(p.userId())))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a doctor account");
        if(repo.linkedDoctorCount(p.userId())>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Doctor account already has a profile");
        String registrationId=requiredRegistration(p.registrationId());requireUniqueRegistration(registrationId,null);
        validateBranches(p.branches());long id;
        try { id=repo.createDoctor(p.userId(),p.fullName().trim(),registrationId,p.specialtyId(),p.department().trim()); }
        catch(DataIntegrityViolationException e) { throw registrationConflictOrOriginal(registrationId,e); }
        saveBranches(id,p.branches());
        users.audit(auth,"CREATE_DOCTOR","doctor",id);return id;
    }
    @Transactional public long createDoctorWithAccount(Authentication auth,DoctorController.DoctorAccountInput p) {
        requireAdmin(auth);
        String email=EmailPolicy.newAddress(p.email());
        if(repo.emailCount(email)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Email already has an account");
        String registrationId=requiredRegistration(p.registrationId());requireUniqueRegistration(registrationId,null);
        validateBranches(p.branches());long userId=repo.createDoctorUser(email,encoder.encode(p.password()),p.fullName().trim());
        long doctorId;
        try { doctorId=repo.createDoctor(userId,p.fullName().trim(),registrationId,p.specialtyId(),p.department().trim()); }
        catch(DataIntegrityViolationException e) { throw registrationConflictOrOriginal(registrationId,e); }
        saveBranches(doctorId,p.branches());
        users.audit(auth,"CREATE_STAFF","app_user",userId);
        users.audit(auth,"CREATE_DOCTOR","doctor",doctorId);
        return doctorId;
    }
    @Transactional public void updateDoctor(Authentication auth,long id,DoctorController.DoctorUpdateInput p) {
        requireAdmin(auth);
        updateProfile(auth,id,repo.doctor(id),p,"UPDATE_DOCTOR");
    }
    @Transactional public boolean updateMine(Authentication auth,DoctorController.DoctorUpdateInput p) {
        if(!"DOCTOR".equals(users.role(auth)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Doctor account required");
        Map<String,Object> doctor=repo.mine(users.id(auth));long id=((Number)doctor.get("id")).longValue();
        return updateProfile(auth,id,doctor,p,"UPDATE_DOCTOR_PROFILE");
    }
    private boolean updateProfile(Authentication auth,long id,Map<String,Object> doctor,DoctorController.DoctorUpdateInput p,String auditAction) {
        Long userId=doctor.get("user_id") instanceof Number value?value.longValue():null;
        if(userId==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Doctor has no linked login account");
        String currentEmail=Objects.toString(doctor.get("email"),"");
        String email=EmailPolicy.changedAddress(p.email(),currentEmail);
        if(repo.emailCountExcept(email,userId)>0)throw duplicateEmail();
        validateBranches(p.branches());
        try {
            repo.updateDoctor(id,p.fullName().trim(),p.specialtyId(),p.department().trim(),p.active());
            repo.updateDoctorAccount(userId,p.fullName().trim(),email);
        } catch(DataIntegrityViolationException e) {
            if(repo.emailCountExcept(email,userId)>0)throw duplicateEmail();
            throw e;
        }
        Set<Long> requested=new HashSet<>();for(var b:p.branches())requested.add(b.branchId());
        for(Map<String,Object> old:repo.branches(id)){long branchId=((Number)old.get("branch_id")).longValue();if(!requested.contains(branchId)){if(repo.futureBookedAtBranch(id,branchId)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Cannot remove a branch with future booked appointments");repo.retireBranch(id,branchId);}}
        saveBranches(id,p.branches());
        users.audit(auth,auditAction,"doctor",id);
        return !email.equalsIgnoreCase(currentEmail.trim());
    }
    @Transactional public void deleteDoctor(Authentication auth,long id) {
        requireAdmin(auth);Map<String,Object> doctor;
        try { doctor=repo.doctor(id); } catch(EmptyResultDataAccessException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Doctor profile not found"); }
        Long userId=doctor.get("user_id") instanceof Number value?value.longValue():null;
        repo.deleteInactiveSchedules(id);
        repo.deleteRetiredUnbookedSlots(id);
        if(repo.dependencyCount(id)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Doctor profile has schedules, appointment or slot records, or leave records and cannot be deleted");
        if(userId!=null){repo.anonymizeAuditEvents(userId);repo.deleteNotifications(userId);}
        repo.deleteBranches(id);repo.deleteDoctor(id);if(userId!=null)repo.deleteUser(userId);
        users.audit(auth,"DELETE_DOCTOR","doctor",id);
    }
    private String requiredRegistration(String value) {
        if(value==null || value.trim().isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Medical registration ID is required");
        return validRegistration(value);
    }
    private String validRegistration(String value) {
        String clean=value.trim().toUpperCase(Locale.ROOT);
        if(!REGISTRATION_ID.matcher(clean).matches())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Medical registration ID must be 3-50 letters, numbers, hyphens, or slashes");
        return clean;
    }
    private void requireUniqueRegistration(String registrationId,Long exceptDoctorId) { if(registrationId!=null && repo.registrationCount(registrationId,exceptDoctorId)>0)throw duplicateRegistration(); }
    private RuntimeException registrationConflictOrOriginal(String registrationId,DataIntegrityViolationException original) { return repo.registrationCount(registrationId,null)>0?duplicateRegistration():original; }
    private ResponseStatusException duplicateRegistration() { return new ResponseStatusException(HttpStatus.CONFLICT,"Medical registration ID already belongs to another doctor"); }
    private ResponseStatusException duplicateEmail() { return new ResponseStatusException(HttpStatus.CONFLICT,"Email is already in use"); }
    private String cleanRoom(String room) { return room==null || room.trim().isEmpty()?null:room.trim(); }
    private void validateBranches(List<DoctorController.BranchAssignmentInput> branches) {
        if(branches==null||branches.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"At least one branch assignment is required");
        Set<Long> ids=new HashSet<>();for(var b:branches){if(!ids.add(b.branchId()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A branch can only be assigned once");if(repo.branchExists(b.branchId())!=1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose an active branch");}
    }
    private void saveBranches(long doctorId,List<DoctorController.BranchAssignmentInput> branches){for(var b:branches)repo.upsertBranch(doctorId,b.branchId(),cleanRoom(b.room()),b.fee());}
    private List<Map<String,Object>> enrich(List<Map<String,Object>> rows){List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> row:rows)result.add(enrichOne(row));return result;}
    private Map<String,Object> enrichOne(Map<String,Object> source){Map<String,Object> row=new LinkedHashMap<>(source);row.put("branches",repo.branches(((Number)row.get("id")).longValue()));return row;}
    private Map<String,Object> assigned(long doctorId,long branchId){if(repo.activeBranchAssignment(doctorId,branchId)!=1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Doctor is not assigned to the selected branch");return repo.branchAssignment(doctorId,branchId);}
    @Transactional public long addSchedule(Authentication auth,long id,DoctorController.ScheduleInput p) {
        requireOwnerOrAdmin(auth,id);
        validateScheduleTime(id,p.scheduleDate(),p.startTime(),p.endTime());
        Map<String,Object> assignment=assigned(id,p.branchId());
        if(repo.overlap(id,p.scheduleDate(),p.startTime(),p.endTime())>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Doctor schedule overlaps on this date");
        if(repo.roomOverlap(id,p.branchId(),(String)assignment.get("room"),p.scheduleDate(),p.startTime(),p.endTime(),-1)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Room schedule overlaps at this branch on this date");
        long scheduleId=repo.addSchedule(id,p.branchId(),p.scheduleDate(),p.startTime(),p.endTime(),p.slotMinutes(),p.capacity());
        generate(id,p.branchId(),p.scheduleDate(),p.startTime(),p.endTime(),p.slotMinutes(),p.capacity());
        users.audit(auth,"CREATE_SCHEDULE","schedule_template",scheduleId);
        events.publishEvent(new ScheduleEvent(id,"A consultation schedule was added for "+p.scheduleDate()+" from "+p.startTime()+" to "+p.endTime()+"."));
        return scheduleId;
    }
    @Transactional public void editSchedule(Authentication auth,long doctorId,long scheduleId,DoctorController.ScheduleInput p) {
        requireOwnerOrAdmin(auth,doctorId);
        Map<String,Object> old=findSchedule(doctorId,scheduleId);
        if(!Boolean.TRUE.equals(old.get("active")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Schedule is inactive");
        validateScheduleTime(doctorId,p.scheduleDate(),p.startTime(),p.endTime());
        Map<String,Object> assignment=assigned(doctorId,p.branchId());
        if(repo.overlapExcept(doctorId,scheduleId,p.scheduleDate(),p.startTime(),p.endTime())>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Doctor schedule overlaps on this date");
        if(repo.roomOverlap(doctorId,p.branchId(),(String)assignment.get("room"),p.scheduleDate(),p.startTime(),p.endTime(),scheduleId)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Room schedule overlaps at this branch on this date");
        repo.retireFutureSlots(doctorId,((Number)old.get("branch_id")).longValue(),localDate(old.get("schedule_date")),localTime(old.get("start_time")),localTime(old.get("end_time")));
        repo.updateSchedule(scheduleId,p.branchId(),p.scheduleDate(),p.startTime(),p.endTime(),p.slotMinutes(),p.capacity());
        generate(doctorId,p.branchId(),p.scheduleDate(),p.startTime(),p.endTime(),p.slotMinutes(),p.capacity());
        users.audit(auth,"UPDATE_SCHEDULE","schedule_template",scheduleId);
        events.publishEvent(new ScheduleEvent(doctorId,"Your consultation schedule changed to "+p.scheduleDate()+" from "+p.startTime()+" to "+p.endTime()+". Existing bookings remain scheduled."));
    }
    @Transactional public void disableSchedule(Authentication auth,long doctorId,long scheduleId) {
        requireOwnerOrAdmin(auth,doctorId);
        Map<String,Object> old=findSchedule(doctorId,scheduleId);
        if(!Boolean.TRUE.equals(old.get("active")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Schedule is already inactive");
        repo.retireFutureSlots(doctorId,((Number)old.get("branch_id")).longValue(),localDate(old.get("schedule_date")),localTime(old.get("start_time")),localTime(old.get("end_time")));
        repo.disableSchedule(scheduleId);
        users.audit(auth,"DISABLE_SCHEDULE","schedule_template",scheduleId);
        events.publishEvent(new ScheduleEvent(doctorId,"A consultation date was disabled. Existing bookings remain scheduled."));
    }
    private Map<String,Object> findSchedule(long doctorId,long scheduleId) {
        try { return repo.schedule(doctorId,scheduleId); }
        catch(EmptyResultDataAccessException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Schedule not found"); }
    }
    private LocalTime localTime(Object value) { return value instanceof java.sql.Time time?time.toLocalTime():(LocalTime)value; }
    private LocalDate localDate(Object value) { return value instanceof java.sql.Date date?date.toLocalDate():(LocalDate)value; }
    private void validateScheduleTime(long doctorId,LocalDate date,LocalTime from,LocalTime to) {
        if(date.isBefore(LocalDate.now()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Schedule date must be today or later");
        if(!to.isAfter(from))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"End time must follow start time");
        if(date.equals(LocalDate.now())&&!from.isAfter(LocalTime.now()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Start time must be in the future");
        if(repo.leaveCount(doctorId,date)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Doctor is on leave on this date");
    }
    private void generate(long doctorId,long branchId,LocalDate date,LocalTime from,LocalTime to,int minutes,int capacity) { LocalDateTime start=date.atTime(from);int created=0;while(created<capacity&&!start.plusMinutes(minutes).isAfter(date.atTime(to))){if(start.isAfter(LocalDateTime.now()))repo.createSlot(doctorId,branchId,start,start.plusMinutes(minutes));start=start.plusMinutes(minutes);created++;} }
    private void requireAdmin(Authentication auth) { if(!"ADMIN".equals(users.role(auth)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Administrator required"); }
    private void requireOwnerOrAdmin(Authentication auth,long doctorId) { if("ADMIN".equals(users.role(auth)))return;Object owner=repo.doctor(doctorId).get("user_id");if(!"DOCTOR".equals(users.role(auth)) || owner==null || ((Number)owner).longValue()!=users.id(auth))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Doctor access required"); }
}
