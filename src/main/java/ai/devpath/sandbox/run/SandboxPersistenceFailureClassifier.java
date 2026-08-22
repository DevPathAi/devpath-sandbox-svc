package ai.devpath.sandbox.run;

import java.sql.SQLException;
import java.sql.SQLTransientException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;

final class SandboxPersistenceFailureClassifier {

  private SandboxPersistenceFailureClassifier() {}

  static boolean isUniqueViolation(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  static boolean isTransient(Throwable failure) {
    Throwable current = failure;
    boolean explicitlyPermanent = false;
    while (current != null) {
      if (current instanceof TransientDataAccessException
          || current instanceof SQLTransientException) {
        return true;
      }
      if (current instanceof SQLException sql) {
        String state = sql.getSQLState();
        if (state != null && (state.startsWith("08")
            || state.startsWith("40")
            || state.startsWith("53")
            || state.startsWith("57P"))) {
          return true;
        }
        if ("23505".equals(state) || (state != null && state.startsWith("22"))) {
          explicitlyPermanent = true;
        }
      }
      if (current instanceof IllegalArgumentException
          || current instanceof DataIntegrityViolationException) {
        explicitlyPermanent = true;
      }
      current = current.getCause();
    }
    // Unknown infrastructure failures are retried conservatively; only known permanent
    // validation/integrity failures skip the burst and remain in the bounded backlog.
    return !explicitlyPermanent;
  }
}
