package lk.sunshine.smartcare.features.patient;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
public class PatientController {
    private final PatientService service;
    public PatientController(PatientService service) { this.service=service; }
    public record Registration(@NotBlank String fullName,@Email @NotBlank String email,@NotBlank @Size(min=8) String password,@NotBlank @Pattern(regexp="[0-9]{12}",message="must be exactly 12 digits") String nic,@NotNull @Past LocalDate dateOfBirth,@NotBlank @Pattern(regexp="[0-9]{10}",message="must be exactly 10 digits") String phone,String address,@Pattern(regexp="IN_APP|IN_APP_AND_TEXT") String contactPreference) {}
    public record Profile(@NotBlank String fullName,@Email @NotBlank String email,@NotBlank @Pattern(regexp="[0-9]{12}",message="must be exactly 12 digits") String nic,@NotBlank @Pattern(regexp="[0-9]{10}",message="must be exactly 10 digits") String phone,String address,@NotBlank @Pattern(regexp="IN_APP|IN_APP_AND_TEXT") String contactPreference) {}
    public record AdminUpdate(@NotBlank String fullName,@Email @NotBlank String email,@NotBlank @Pattern(regexp="[0-9]{12}",message="must be exactly 12 digits") String nic,@NotNull @Past LocalDate dateOfBirth,@NotBlank @Pattern(regexp="[0-9]{10}",message="must be exactly 10 digits") String phone,String address,@NotNull Boolean active,@Size(min=8) String password) {}
    public record Contact(@NotBlank String fullName,@NotBlank String relationship,@NotBlank @Pattern(regexp="[0-9]{10}",message="must be exactly 10 digits") String phone) {}
    @PostMapping("/api/auth/register") @ResponseStatus(HttpStatus.CREATED) public Map<String,String> register(@Valid @RequestBody Registration r) { service.register(r);return Map.of("message","Account created"); }
    @GetMapping("/api/admin/patients") public List<Map<String,Object>> allForAdmin(Authentication auth) { return service.allForAdmin(auth); }
    @PostMapping("/api/admin/patients") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> registerByAdmin(Authentication auth,@Valid @RequestBody Registration r) { return Map.of("id",service.registerByAdmin(auth,r)); }
    @PutMapping("/api/admin/patients/{userId}") public Map<String,String> updateByAdmin(Authentication auth,@PathVariable long userId,@Valid @RequestBody AdminUpdate update) { service.updateByAdmin(auth,userId,update);return Map.of("message","Patient account saved"); }
    @DeleteMapping("/api/admin/patients/{userId}") public Map<String,String> deleteByAdmin(Authentication auth,@PathVariable long userId) { service.deactivateByAdmin(auth,userId);return Map.of("message","Patient account deactivated"); }
    @DeleteMapping("/api/admin/patients/{userId}/permanent") public Map<String,String> permanentlyDeleteByAdmin(Authentication auth,@PathVariable long userId) { service.permanentlyDeleteByAdmin(auth,userId);return Map.of("message","Patient account permanently deleted"); }
    @GetMapping("/api/patient/profile") public Map<String,Object> profile(Authentication auth) { return service.profile(auth); }
    @PutMapping("/api/patient/profile") public Map<String,Object> update(Authentication auth,@Valid @RequestBody Profile p) { return Map.of("message","Profile saved","emailChanged",service.update(auth,p)); }
    @GetMapping("/api/patient/contacts") public List<Map<String,Object>> contacts(Authentication auth) { return service.contacts(auth); }
    @PostMapping("/api/patient/contacts") public Map<String,String> add(Authentication auth,@Valid @RequestBody Contact c) { service.addContact(auth,c);return Map.of("message","Contact added"); }
    @DeleteMapping("/api/patient/contacts/{id}") public Map<String,String> delete(Authentication auth,@PathVariable long id) { service.deleteContact(auth,id);return Map.of("message","Contact removed"); }
}
