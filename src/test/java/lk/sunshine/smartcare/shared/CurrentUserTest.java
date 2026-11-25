package lk.sunshine.smartcare.shared;

import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrentUserTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final CurrentUser users=new CurrentUser(db);
    private final Authentication auth=new UsernamePasswordAuthenticationToken("qa@gmail.com","unused",List.of());

    @Test void activeAccountCanResolveIdentityAndRole() {
        when(db.queryForObject("SELECT id FROM app_user WHERE email=? AND active=1",Long.class,"qa@gmail.com")).thenReturn(7L);
        when(db.queryForObject("SELECT role FROM app_user WHERE email=? AND active=1",String.class,"qa@gmail.com")).thenReturn("PATIENT");
        assertEquals(7L,users.id(auth));
        assertEquals("PATIENT",users.role(auth));
    }
    @Test void disabledOrDeletedAccountCannotReuseAuthenticatedSession() {
        when(db.queryForObject("SELECT id FROM app_user WHERE email=? AND active=1",Long.class,"qa@gmail.com")).thenThrow(new EmptyResultDataAccessException(1));
        when(db.queryForObject("SELECT role FROM app_user WHERE email=? AND active=1",String.class,"qa@gmail.com")).thenThrow(new EmptyResultDataAccessException(1));
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,()->users.id(auth)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,()->users.role(auth)).getStatusCode());
    }
    @Test void unauthenticatedRequestsDoNotResolveAnAccount() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,()->users.id(null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,()->users.role(new UsernamePasswordAuthenticationToken("qa@gmail.com","unused"))).getStatusCode());
        verifyNoInteractions(db);
    }
}
