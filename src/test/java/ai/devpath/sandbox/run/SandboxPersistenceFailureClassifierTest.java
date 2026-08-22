package ai.devpath.sandbox.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SandboxPersistenceFailureClassifierTest {

  @Test
  void recognizesPostgresUniqueViolationAnywhereInCauseChain() {
    var failure = new DataIntegrityViolationException(
        "constraint uq_sandbox_one_active_user",
        new SQLException("duplicate", "23505"));

    assertThat(SandboxPersistenceFailureClassifier.isUniqueViolation(failure)).isTrue();
  }

  @Test
  void doesNotRelabelOtherIntegrityFailuresAsBusy() {
    var failure = new DataIntegrityViolationException(
        "check constraint",
        new SQLException("bad status", "23514"));

    assertThat(SandboxPersistenceFailureClassifier.isUniqueViolation(failure)).isFalse();
  }
}
