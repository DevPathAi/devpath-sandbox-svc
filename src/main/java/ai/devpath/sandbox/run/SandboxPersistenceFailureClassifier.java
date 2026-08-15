package ai.devpath.sandbox.run;

import java.sql.SQLException;

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
}
