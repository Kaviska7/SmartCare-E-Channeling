package lk.sunshine.smartcare.features.queue;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

@RestController
@RequestMapping("/api/queue")
public class QueueController {
    private final QueueService service;
    public QueueController(QueueService service) { this.service=service; }
    @GetMapping public List<Map<String,Object>> today(Authentication auth,@RequestParam(required=false) Long branchId,@RequestParam(required=false) Long doctorId,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date) { return service.list(auth,date,branchId,doctorId); }
    @GetMapping("/find") public List<Map<String,Object>> find(Authentication auth,@RequestParam String search) { return service.findAppointments(auth,search); }
    @PostMapping("/check-in/{appointmentId}") public Map<String,Object> checkIn(Authentication auth,@PathVariable long appointmentId) { return Map.of("tokenNumber",service.checkIn(auth,appointmentId)); }
    @PostMapping("/call-next/{doctorId}") public Map<String,Object> call(Authentication auth,@PathVariable long doctorId,@RequestParam long branchId,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date) { Long token=service.callNext(auth,doctorId,branchId,date);return token==null?Map.of("message","No waiting patients"):Map.of("tokenId",token); }
    public record RescheduleRequest(@Positive long slotId) {}
    @DeleteMapping("/{tokenId}") public Map<String,String> delete(Authentication auth,@PathVariable long tokenId) { service.cancel(auth,tokenId);return Map.of("message","Visit cancelled and removed from the live queue"); }
    @PutMapping("/{tokenId}/slot") public Map<String,String> reschedule(Authentication auth,@PathVariable long tokenId,@Valid @RequestBody RescheduleRequest input) { service.reschedule(auth,tokenId,input.slotId());return Map.of("message","Visit rescheduled; check in again on the appointment date"); }
    @PostMapping("/{tokenId}/complete") public Map<String,String> complete(Authentication auth,@PathVariable long tokenId) { service.finish(auth,tokenId,false);return Map.of("message","Consultation completed"); }
    @PostMapping("/{tokenId}/skip") public Map<String,String> skip(Authentication auth,@PathVariable long tokenId) { service.finish(auth,tokenId,true);return Map.of("message","Token skipped"); }
}
