package ai.devpath.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CiWorkflowContractTest {

  private static final String FINAL_SHARED =
      "d3cf41faf21d00b815b398a7492af5506390151a";
  private static final String ET8_SHARED =
      "2b03c38934fdd19332da59107e4330a3af92d078";
  private static final String HISTORICAL_SHARED =
      "78e2c6150644f81eff9af9664253ff0b9039f927";

  @Test
  void bothBuildConsumersPinFinalSharedAndBuildProvesHistoricalUpgrade() throws Exception {
    String workflow = Files.readString(Path.of(".github/workflows/ci.yml"));

    assertThat(count(workflow, "ref: " + FINAL_SHARED)).isEqualTo(2);
    assertThat(workflow).contains("ref: " + ET8_SHARED);
    assertThat(workflow).contains("ref: " + HISTORICAL_SHARED);
    assertThat(workflow).contains("path: .ci/devpath-shared-et8");
    assertThat(workflow).contains("path: .ci/devpath-shared-history");
    assertThat(workflow).contains(
        "--tests \"ai.devpath.sandbox.SharedMigrationUpgradePathTest\"");
    assertThat(workflow).contains("HISTORICAL_SHARED_MIGRATIONS:");
    assertThat(workflow).contains("ET8_SHARED_MIGRATIONS:");
    assertThat(workflow).contains("FINAL_SHARED_VERSION: 202608161011");
  }

  private static int count(String value, String needle) {
    return value.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
  }
}
