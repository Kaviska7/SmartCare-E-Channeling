package lk.sunshine.smartcare.features.doctor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/doctors")
public class DoctorController {
    private final DoctorService service;
    public DoctorController(DoctorService service) { this.service=service; }
    public record BranchAssignmentInput(@Positive long branchId,String room,@DecimalMin("0.0") double fee) {}
    public record DoctorInput(@Positive Long userId,@NotBlank String fullName,@NotBlank @Size(max=60) String registrationId,@Positive long specialtyId,@NotBlank String department,boolean active,@NotEmpty List<@Valid BranchAssignmentInput> branches) {}
    public record DoctorUpdateInput(@NotBlank String fullName,@Email @NotBlank String email,@Positive long specialtyId,@NotBlank String department,boolean active,@NotEmpty List<@Valid BranchAssignmentInput> branches) {}
    public record DoctorAccountInput(@Email @NotBlank String email,@NotBlank @Size(min=8) String password,@NotBlank String fullName,@NotBlank @Size(max=60) String registrationId,@Positive long specialtyId,@NotBlank String department,@NotEmpty List<@Valid BranchAssignmentInput> branches) {}
    public record SpecialtyInput(@NotBlank String name) {}
    public record ScheduleInput(@NotNull LocalDate scheduleDate,@NotNull LocalTime startTime,@NotNull LocalTime endTime,@Min(5) @Max(120) int slotMinutes,@Min(1) @Max(100) int capacity,@Positive long branchId) {}
    public record LeaveInput(@NotNull LocalDate date,@Size(max=200) String reason) {}
    @GetMapping public List<Map<String,Object>> all(@RequestParam(defaultValue="") String specialty,@RequestParam(required=false) Long branchId) { return service.doctors(specialty,branchId); }
    @GetMapping("/admin") public List<Map<String,Object>> adminList(Authentication auth,@RequestParam(defaultValue="") String specialty) { return service.allForAdmin(auth,specialty); }
    @GetMapping("/specialties") public List<Map<String,Object>> specialties() { return service.specialties(); }
    @PostMapping("/specialties") public Map<String,Object> createSpecialty(Authentication auth,@Valid @RequestBody SpecialtyInput p) { return Map.of("id",service.createSpecialty(auth,p.name())); }
    @DeleteMapping("/specialties/{id}") public Map<String,String> deleteSpecialty(Authentication auth,@PathVariable long id) { service.deleteSpecialty(auth,id);return Map.of("message","Specialty removed"); }
    @GetMapping("/me") public Map<String,Object> mine(Authentication auth) { return service.mine(auth); }
    @PutMapping("/me") public Map<String,Object> updateMine(Authentication auth,@Valid @RequestBody DoctorUpdateInput p) { return Map.of("message","Doctor profile saved","emailChanged",service.updateMine(auth,p)); }
    @GetMapping("/{id}/slots") public List<Map<String,Object>> slots(@PathVariable long id,@RequestParam(required=false) Long branchId) { return service.slots(id,branchId); }
    @GetMapping("/{id}/schedules") public List<Map<String,Object>> schedules(Authentication auth,@PathVariable long id) { return service.schedules(auth,id); }
    @PostMapping public Map<String,Object> create(Authentication auth,@Valid @RequestBody DoctorInput p) { return Map.of("id",service.createDoctor(auth,p)); }
    @PostMapping("/with-account") public Map<String,Object> createWithAccount(Authentication auth,@Valid @RequestBody DoctorAccountInput p) { return Map.of("id",service.createDoctorWithAccount(auth,p)); }
    @PutMapping("/{id}") public Map<String,String> update(Authentication auth,@PathVariable long id,@Valid @RequestBody DoctorUpdateInput p) { service.updateDoctor(auth,id,p);return Map.of("message","Doctor saved"); }
    @DeleteMapping("/{id}") public Map<String,String> delete(Authentication auth,@PathVariable long id) { service.deleteDoctor(auth,id);return Map.of("message","Doctor profile deleted"); }
    @PostMapping("/{id}/schedules") public Map<String,Object> schedule(Authentication auth,@PathVariable long id,@Valid @RequestBody ScheduleInput p) { return Map.of("id",service.addSchedule(auth,id,p)); }
    @PutMapping("/{id}/schedules/{scheduleId}") public Map<String,String> editSchedule(Authentication auth,@PathVariable long id,@PathVariable long scheduleId,@Valid @RequestBody ScheduleInput p) { service.editSchedule(auth,id,scheduleId,p);return Map.of("message","Schedule saved"); }
    @DeleteMapping("/{id}/schedules/{scheduleId}") public Map<String,String> disableSchedule(Authentication auth,@PathVariable long id,@PathVariable long scheduleId) { service.disableSchedule(auth,id,scheduleId);return Map.of("message","Schedule disabled"); }
    @GetMapping("/{id}/leave") public List<Map<String,Object>> leaves(Authentication auth,@PathVariable long id) { return service.leaves(auth,id); }
    @PostMapping("/{id}/leave") public Map<String,Object> addLeave(Authentication auth,@PathVariable long id,@Valid @RequestBody LeaveInput p) { return Map.of("id",service.addLeave(auth,id,p.date(),p.reason())); }
    @DeleteMapping("/{id}/leave/{leaveId}") public Map<String,String> deleteLeave(Authentication auth,@PathVariable long id,@PathVariable long leaveId) { service.deleteLeave(auth,id,leaveId);return Map.of("message","Leave removed"); }
}
