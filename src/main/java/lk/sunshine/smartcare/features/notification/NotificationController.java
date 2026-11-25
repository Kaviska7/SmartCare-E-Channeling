package lk.sunshine.smartcare.features.notification;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service=service; }
    @GetMapping public List<Map<String,Object>> inbox(Authentication auth) { return service.inbox(auth); }
    @PostMapping("/{id}/read") public Map<String,String> read(Authentication auth,@PathVariable long id) { service.read(auth,id);return Map.of("message","Marked read"); }
}
