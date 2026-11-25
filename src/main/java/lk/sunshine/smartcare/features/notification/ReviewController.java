package lk.sunshine.smartcare.features.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {
    private final ReviewService service;
    public ReviewController(ReviewService service) { this.service=service; }
    public record ReviewInput(@Positive long appointmentId,@Min(1) @Max(5) int rating,@Size(max=500) String comment) {}
    public record ReviewUpdate(@Min(1) @Max(5) int rating,@Size(max=500) String comment) {}
    @GetMapping public List<Map<String,Object>> own(Authentication auth) { return service.own(auth); }
    @GetMapping("/admin") public List<Map<String,Object>> allForAdmin(Authentication auth) { return service.allForAdmin(auth); }
    @PostMapping public Map<String,Object> create(Authentication auth,@Valid @RequestBody ReviewInput input) {
        return Map.of("id",service.create(auth,input.appointmentId(),input.rating(),input.comment()));
    }
    @PutMapping("/{id}") public Map<String,String> update(Authentication auth,@PathVariable long id,@Valid @RequestBody ReviewUpdate input) { service.update(auth,id,input.rating(),input.comment());return Map.of("message","Feedback saved"); }
    @DeleteMapping("/{id}") public Map<String,String> delete(Authentication auth,@PathVariable long id) { service.delete(auth,id);return Map.of("message","Feedback deleted"); }
}
