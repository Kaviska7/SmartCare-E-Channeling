package lk.sunshine.smartcare.features.patient;

import jakarta.validation.Validation;
import lk.sunshine.smartcare.shared.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PatientServiceTest {
    private final PatientRepository repo=mock(PatientRepository.class);
    private final CurrentUser users=mock(CurrentUser.class);
    private final Authentication auth=mock(Authentication.class);
    private final PasswordEncoder encoder=mock(PasswordEncoder.class);
    private final PatientService service=new PatientService(repo,encoder,users);

    @Test void patientCanChangeUniqueEmailAndNicTogether() {
        when(users.role(auth)).thenReturn("PATIENT");
        when(users.id(auth)).thenReturn(7L);
        when(auth.getName()).thenReturn("old@example.test");
        var profile=new PatientController.Profile("Patient","new@gmail.com","199400000002","0771234567","Home","IN_APP");

        assertThat(service.update(auth,profile)).isTrue();
        verify(repo).update(7L,"Patient","new@gmail.com","199400000002","0771234567","Home","IN_APP");
        verify(users).audit(auth,"UPDATE_PROFILE","patient",null);
    }

    @Test void duplicateNicCannotReplaceAnotherPatientsNic() {
        when(users.role(auth)).thenReturn("PATIENT");
        when(users.id(auth)).thenReturn(7L);
        when(repo.nicCountExcept("199400000002",7L)).thenReturn(1);

        when(auth.getName()).thenReturn("old@example.test");
        assertThatThrownBy(() -> service.update(auth,new PatientController.Profile("Patient","new@gmail.com","199400000002","0771234567",null,"IN_APP")))
            .isInstanceOf(ResponseStatusException.class);
        verify(repo,never()).update(anyLong(),any(),any(),any(),any(),any(),any());
    }

    @Test void nicAndPhoneRequireExactDigitCounts() {
        try (var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            var valid=new PatientController.Profile("Patient","p@example.test","199400000002","0771234567",null,"IN_APP");
            assertThat(validator.validate(valid)).isEmpty();
            assertThat(validator.validate(new PatientController.Profile("Patient","p@example.test","199400000002","0771234567",null,"IN_APP_AND_TEXT"))).isEmpty();
            assertThat(validator.validate(new PatientController.Profile("Patient","p@example.test","199400000002","0771234567",null,"SMS"))).isNotEmpty();
            assertThat(validator.validate(new PatientController.Profile("Patient","p@example.test","1234","0771234567",null,"IN_APP"))).isNotEmpty();
            assertThat(validator.validate(new PatientController.Profile("Patient","p@example.test","199400000002","+9477123456",null,"IN_APP"))).isNotEmpty();
            assertThat(validator.validate(new PatientController.Profile("Patient","p@example.test","199400000002","077123456",null,"IN_APP"))).isNotEmpty();
        }
    }

    @Test void selfRegistrationCanOptInButAdminRegistrationStartsOff() {
        var registration=new PatientController.Registration("Patient","p@gmail.com","password","199400000002",java.time.LocalDate.of(1994,1,1),"0771234567","Home","IN_APP_AND_TEXT");
        when(repo.createUser(any(),any(),any())).thenReturn(17L);
        service.register(registration);
        verify(repo).createPatient(17L,"199400000002","1994-01-01","0771234567","Home","IN_APP_AND_TEXT");
        clearInvocations(repo);
        when(users.role(auth)).thenReturn("ADMIN");
        service.registerByAdmin(auth,registration);
        verify(repo).createPatient(17L,"199400000002","1994-01-01","0771234567","Home","IN_APP");
    }

    @Test void administratorCanListPatientsWithRequiredFields() {
        when(users.role(auth)).thenReturn("ADMIN");
        var patient=Map.<String,Object>of(
            "id",17L,"full_name","Patient","email","p@example.test","nic","199400000002",
            "date_of_birth",LocalDate.of(1994,1,1),"phone","0771234567","address","Home","active",true);
        when(repo.allForAdmin()).thenReturn(List.of(patient));

        assertThat(service.allForAdmin(auth)).singleElement().satisfies(item -> {
            assertThat(item).containsKeys("id","full_name","email","nic","date_of_birth","phone","address","active");
            assertThat(item.get("email")).isEqualTo("p@example.test");
        });
    }

    @Test void nonAdministratorCannotListPatients() {
        when(users.role(auth)).thenReturn("MANAGER");

        assertThatThrownBy(() -> service.allForAdmin(auth))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Administrator required");
        verify(repo,never()).allForAdmin();
    }

    @Test void administratorCanUpdateReactivateAndResetPatientPassword() {
        when(users.role(auth)).thenReturn("ADMIN");
        when(repo.adminState(17L)).thenReturn(Map.of("id",17L,"email","old@example.test","active",false));
        when(encoder.encode("newpassword")).thenReturn("encoded");
        var update=new PatientController.AdminUpdate("Updated Patient","new@gmail.com","199400000002",
            LocalDate.of(1994,1,1),"0771234567","New address",true,"newpassword");

        service.updateByAdmin(auth,17L,update);

        verify(repo).updateByAdmin(17L,"Updated Patient","new@gmail.com","199400000002","1994-01-01","0771234567","New address",true);
        verify(repo).updatePassword(17L,"encoded");
        verify(users).audit(auth,"UPDATE_PATIENT","app_user",17L);
        verify(users).audit(auth,"RESET_PATIENT_PASSWORD","app_user",17L);
        verify(users).audit(auth,"REACTIVATE_PATIENT","app_user",17L);
    }

    @Test void nonAdministratorCannotDeactivatePatient() {
        when(users.role(auth)).thenReturn("MANAGER");

        assertThatThrownBy(() -> service.deactivateByAdmin(auth,17L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Administrator required");
        verify(repo,never()).deactivate(anyLong());
    }

    @Test void inactiveDependencyFreePatientCanBePermanentlyDeleted() {
        when(users.role(auth)).thenReturn("ADMIN");
        when(repo.lockedAdminState(17L)).thenReturn(Map.of("id",17L,"active",false,"patient_id",41L));
        when(repo.permanentlyDelete(17L,41L)).thenReturn(1);

        service.permanentlyDeleteByAdmin(auth,17L);

        verify(repo).permanentlyDelete(17L,41L);
        verify(users).audit(auth,"PERMANENT_DELETE_PATIENT","app_user",17L);
    }

    @Test void activePatientMustBeDeactivatedBeforePermanentDeletion() {
        when(users.role(auth)).thenReturn("ADMIN");
        when(repo.lockedAdminState(17L)).thenReturn(Map.of("id",17L,"active",true,"patient_id",41L));

        assertThatThrownBy(() -> service.permanentlyDeleteByAdmin(auth,17L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Deactivate the patient account");
        verify(repo,never()).permanentlyDelete(anyLong(),anyLong());
    }

    @Test void patientAppointmentHistoryBlocksPermanentDeletion() {
        when(users.role(auth)).thenReturn("ADMIN");
        when(repo.lockedAdminState(17L)).thenReturn(Map.of("id",17L,"active",false,"patient_id",41L));
        when(repo.appointmentCountLocked(41L)).thenReturn(1);

        assertThatThrownBy(() -> service.permanentlyDeleteByAdmin(auth,17L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("appointment, payment, or queue history");
        verify(repo,never()).permanentlyDelete(anyLong(),anyLong());
    }

    @Test void nonAdministratorCannotPermanentlyDeletePatient() {
        when(users.role(auth)).thenReturn("MANAGER");

        assertThatThrownBy(() -> service.permanentlyDeleteByAdmin(auth,17L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Administrator required");
        verify(repo,never()).lockedAdminState(anyLong());
    }
}
