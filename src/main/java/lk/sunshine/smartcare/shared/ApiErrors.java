package lk.sunshine.smartcare.shared;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log=LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<Map<String,String>> status(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason() == null ? "Request failed" : e.getReason())); }
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<Map<String,String>> validation(MethodArgumentNotValidException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getBindingResult().getFieldErrors().stream().findFirst().map(x -> x.getField()+": "+x.getDefaultMessage()).orElse("Invalid input"))); }
    @ExceptionHandler(DataAccessException.class) ResponseEntity<Map<String,String>> database(DataAccessException e) { log.error("Database operation failed",e);return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "The data could not be saved. Check for duplicates or try again.")); }
}
