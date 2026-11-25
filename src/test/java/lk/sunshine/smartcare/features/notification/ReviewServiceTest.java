package lk.sunshine.smartcare.features.notification;

import lk.sunshine.smartcare.shared.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ReviewServiceTest {
    private final ReviewRepository repo=mock(ReviewRepository.class);
    private final CurrentUser users=mock(CurrentUser.class);
    private final Authentication auth=mock(Authentication.class);
    private final ReviewService service=new ReviewService(repo,users);

    @Test void onlyOwnerOfCompletedAppointmentCanCreateFeedback() {
        when(users.role(auth)).thenReturn("PATIENT");when(users.id(auth)).thenReturn(7L);
        when(repo.appointment(12L)).thenReturn(Map.of("user_id",7L,"patient_id",3L,"status","COMPLETED"));
        when(repo.create(12L,3L,5,"Good visit")).thenReturn(21L);
        assertThat(service.create(auth,12L,5," Good visit ")).isEqualTo(21L);
        verify(users).audit(auth,"CREATE_FEEDBACK","appointment_review",21L);

        when(repo.appointment(13L)).thenReturn(Map.of("user_id",8L,"patient_id",4L,"status","COMPLETED"));
        assertThatThrownBy(() -> service.create(auth,13L,5,""))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("another patient");
        when(repo.appointment(14L)).thenReturn(Map.of("user_id",7L,"patient_id",3L,"status","NO_SHOW"));
        assertThatThrownBy(() -> service.create(auth,14L,5,""))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Only completed");
    }

    @Test void softDeletedFeedbackCanBeReplaced() {
        when(users.role(auth)).thenReturn("PATIENT");when(users.id(auth)).thenReturn(7L);
        when(repo.appointment(12L)).thenReturn(Map.of("user_id",7L,"patient_id",3L,"status","COMPLETED"));
        when(repo.byAppointment(12L)).thenReturn(List.of(Map.of("id",21L,"deleted_at",Timestamp.valueOf("2026-01-01 00:00:00"))));
        assertThat(service.create(auth,12L,4,"Replacement")).isEqualTo(21L);
        verify(repo).restore(21L,4,"Replacement");
    }

    @Test void patientCanUpdateAndSoftDeleteOwnedFeedback() {
        when(users.role(auth)).thenReturn("PATIENT");when(users.id(auth)).thenReturn(7L);
        when(repo.updateOwned(21L,7L,4,"Updated")).thenReturn(1);
        service.update(auth,21L,4," Updated ");
        verify(users).audit(auth,"UPDATE_FEEDBACK","appointment_review",21L);
        when(repo.softDeleteOwned(21L,7L,7L)).thenReturn(1);
        service.delete(auth,21L);
        verify(users).audit(auth,"DELETE_FEEDBACK","appointment_review",21L);
    }

    @Test void administratorCanListAndDeleteButCannotEditFeedback() {
        when(users.role(auth)).thenReturn("ADMIN");when(users.id(auth)).thenReturn(1L);
        when(repo.allForAdmin()).thenReturn(List.of(Map.of("id",21L,"rating",5)));
        assertThat(service.allForAdmin(auth)).hasSize(1);
        when(repo.softDeleteAny(21L,1L)).thenReturn(1);
        service.delete(auth,21L);
        verify(repo).softDeleteAny(21L,1L);
        assertThatThrownBy(() -> service.update(auth,21L,1,"changed"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Patient account required");
    }
}
