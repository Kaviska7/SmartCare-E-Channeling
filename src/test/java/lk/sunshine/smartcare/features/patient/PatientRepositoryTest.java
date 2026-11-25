package lk.sunshine.smartcare.features.patient;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PatientRepositoryTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final PatientRepository repo=new PatientRepository(db);

    @Test void permanentDeletionRemovesContactsBeforeProfileAndLogin() {
        when(db.update(startsWith("DELETE FROM app_user"),eq(17L))).thenReturn(1);

        assertThat(repo.permanentlyDelete(17L,41L)).isEqualTo(1);

        InOrder order=inOrder(db);
        order.verify(db).update(startsWith("DELETE FROM emergency_contact"),eq(41L));
        order.verify(db).update(startsWith("DELETE FROM patient"),eq(41L),eq(17L));
        order.verify(db).update(startsWith("DELETE FROM app_user"),eq(17L));
    }
}
