package lk.sunshine.smartcare.features.appointment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {
    private final AppointmentService service;

    public AppointmentController(AppointmentService service) {
        this.service = service;
    }

    public record SlotRequest(@Positive long slotId, @Positive Long branchId, String paymentMethod) {
    }

    public record StaffBookingRequest(@Positive long patientId, @Positive long slotId) {
    }

    public record PaymentRequest(@NotBlank String choice) {
    }

    @GetMapping
    public List<Map<String, Object>> list(Authentication auth) {
        return service.list(auth);
    }

    @GetMapping("/patients")
    public List<Map<String, Object>> searchPatients(Authentication auth, @RequestParam String search) {
        return service.searchPatients(auth, search);
    }

    @PostMapping
    public Map<String, Object> book(Authentication auth, @Valid @RequestBody SlotRequest r) {
        return Map.of("id", service.book(auth, r.slotId(), r.branchId(), r.paymentMethod()));
    }

    @PostMapping("/staff-book")
    public Map<String, Object> staffBook(Authentication auth, @Valid @RequestBody StaffBookingRequest r) {
        return Map.of("id", service.bookForPatient(auth, r.patientId(), r.slotId()));
    }

    @PutMapping("/{id}/slot")
    public Map<String, String> reschedule(Authentication auth, @PathVariable long id,
            @Valid @RequestBody SlotRequest r) {
        service.reschedule(auth, id, r.slotId());
        return Map.of("message", "Appointment rescheduled");
    }

    @PostMapping("/{id}/confirm")
    public Map<String, String> confirm(Authentication auth, @PathVariable long id) {
        service.confirm(auth, id);
        return Map.of("message", "Appointment confirmed");
    }

    @PostMapping("/{id}/cancel")
    public Map<String, String> cancel(Authentication auth, @PathVariable long id) {
        service.cancel(auth, id);
        return Map.of("message", "Appointment cancelled");
    }

    @PostMapping("/{id}/payment")
    public Map<String, Object> payment(Authentication auth, @PathVariable long id,
            @Valid @RequestBody PaymentRequest r) {
        return service.payment(auth, id, r.choice());
    }

    @GetMapping("/{id}/payment")
    public Map<String, Object> receipt(Authentication auth, @PathVariable long id) {
        return service.receipt(auth, id);
    }
}
