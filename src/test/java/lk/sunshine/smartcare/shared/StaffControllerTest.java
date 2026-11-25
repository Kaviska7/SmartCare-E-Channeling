package lk.sunshine.smartcare.shared;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StaffControllerTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final PasswordEncoder encoder=mock(PasswordEncoder.class);
    private final CurrentUser users=mock(CurrentUser.class);
    private final Authentication auth=mock(Authentication.class);
    private final StaffController controller=new StaffController(db,encoder,users);

    @Test void newStaffAccountRequiresGmailAddress() {
        when(users.role(auth)).thenReturn("ADMIN");
        var input=new StaffController.StaffInput("manager@example.test","Manager","password","MANAGER");

        assertThatThrownBy(() -> controller.create(auth,input))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("valid @gmail.com address");
        verifyNoInteractions(db);
    }

    @Test void nonAdministratorCannotListStaff() {
        when(users.role(auth)).thenReturn("MANAGER");

        assertThatThrownBy(() -> controller.all(auth))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Administrator required");
        verifyNoInteractions(db);
    }

    @Test void administratorCannotDeactivateOwnAccount() {
        when(users.role(auth)).thenReturn("ADMIN");
        when(users.id(auth)).thenReturn(7L);
        when(db.queryForList(anyString(),eq(7L))).thenReturn(List.of(Map.of("id",7L,"role","ADMIN","active",true)));

        assertThatThrownBy(() -> controller.deactivate(auth,7L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("own administrator account");
        verify(db,never()).update(startsWith("UPDATE app_user"),anyLong());
    }

    @Test void finalActiveAdministratorCannotBeDeactivated() {
        when(users.role(auth)).thenReturn("ADMIN");
        when(users.id(auth)).thenReturn(1L);
        when(db.queryForList(anyString(),eq(8L))).thenReturn(List.of(Map.of("id",8L,"role","ADMIN","active",true)));
        when(db.queryForObject(anyString(),eq(Integer.class),eq(8L))).thenReturn(0);

        assertThatThrownBy(() -> controller.deactivate(auth,8L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("final active administrator");
        verify(db,never()).update(startsWith("UPDATE app_user"),anyLong());
    }

    @Test void inactiveDependencyFreeStaffAccountCanBePermanentlyDeleted() {
        when(users.role(auth)).thenReturn("ADMIN");
        when(users.id(auth)).thenReturn(1L);
        when(db.queryForList(anyString(),eq(8L))).thenReturn(List.of(Map.of("id",8L,"role","MANAGER","active",false)));
        when(db.queryForObject(anyString(),eq(Integer.class),eq(8L))).thenReturn(0);
        when(db.update(startsWith("DELETE FROM app_user"),eq(8L))).thenReturn(1);

        controller.permanentlyDelete(auth,8L);

        verify(db).update(startsWith("DELETE FROM app_user"),eq(8L));
        verify(db).update(startsWith("INSERT INTO audit_event"),eq(1L),eq("PERMANENT_DELETE_STAFF"),eq(8L));
    }

    @Test void activeStaffAccountCannotBePermanentlyDeleted() {
        when(users.role(auth)).thenReturn("ADMIN");
        when(users.id(auth)).thenReturn(1L);
        when(db.queryForList(anyString(),eq(8L))).thenReturn(List.of(Map.of("id",8L,"role","RECEPTION","active",true)));

        assertThatThrownBy(() -> controller.permanentlyDelete(auth,8L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Deactivate the staff account");
        verify(db,never()).update(startsWith("DELETE FROM app_user"),anyLong());
    }

    @Test void staffAuditHistoryBlocksPermanentDeletion() {
        when(users.role(auth)).thenReturn("ADMIN");
        when(users.id(auth)).thenReturn(1L);
        when(db.queryForList(anyString(),eq(8L))).thenReturn(List.of(Map.of("id",8L,"role","MANAGER","active",false)));
        when(db.queryForObject(contains("FROM notification"),eq(Integer.class),eq(8L))).thenReturn(0);
        when(db.queryForObject(contains("FROM audit_event"),eq(Integer.class),eq(8L))).thenReturn(1);

        assertThatThrownBy(() -> controller.permanentlyDelete(auth,8L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("audit history");
        verify(db,never()).update(startsWith("DELETE FROM app_user"),anyLong());
    }

    @Test void nonAdministratorCannotPermanentlyDeleteStaff() {
        when(users.role(auth)).thenReturn("RECEPTION");

        assertThatThrownBy(() -> controller.permanentlyDelete(auth,8L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Administrator required");
        verifyNoInteractions(db);
    }
}
