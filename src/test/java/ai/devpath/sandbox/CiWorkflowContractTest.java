package ai.devpath.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CiWorkflowContractTest {

  private static final String FINAL_SHARED =
      "96a5cdb9d95689759afb229b4d1e29b9bd221793";
  private static final String ET8_SHARED =
      "2b03c38934fdd19332da59107e4330a3af92d078";
  private static final String HISTORICAL_SHARED =
      "78e2c6150644f81eff9af9664253ff0b9039f927";

  @Test
  void migrationBuildPinsFinalSharedBeforeApplicationUsesImmutableRelease() throws Exception {
    String workflow = Files.readString(Path.of(".github/workflows/ci.yml"));

    assertThat(count(workflow, "ref: " + FINAL_SHARED)).isEqualTo(1);
    assertThat(workflow).contains("ref: " + ET8_SHARED);
    assertThat(workflow).contains("ref: " + HISTORICAL_SHARED);
    assertThat(countLine(workflow, "path: .ci/devpath-shared-final")).isEqualTo(1);
    assertThat(countLine(workflow, "path: .ci/devpath-shared")).isZero();
    assertThat(workflow).contains("path: .ci/devpath-shared-et8");
    assertThat(workflow).contains("path: .ci/devpath-shared-history");
    assertThat(workflow).contains(
        "--tests \"ai.devpath.sandbox.SharedMigrationUpgradePathTest\"");
    assertThat(workflow).contains("HISTORICAL_SHARED_MIGRATIONS:");
    assertThat(workflow).contains("ET8_SHARED_MIGRATIONS:");
    assertThat(workflow).contains("FINAL_SHARED_VERSION: 202608161011");

    int migrationBuild = workflow.indexOf(
        "./gradlew test --tests \"ai.devpath.sandbox.SharedMigrationUpgradePathTest\"");
    int immutableBuild = workflow.indexOf(
        "./gradlew --refresh-dependencies build \"-Dgroups=!docker\"");
    int bootJarUpload = workflow.indexOf("name: Preserve exact bootJar input");
    int bootJarDownload = workflow.indexOf("name: Download exact bootJar input");
    assertThat(migrationBuild).isGreaterThanOrEqualTo(0);
    assertThat(immutableBuild).isGreaterThan(migrationBuild);
    assertThat(bootJarUpload).isGreaterThan(immutableBuild);
    assertThat(bootJarDownload).isGreaterThan(bootJarUpload);
    assertThat(count(workflow, "-PdevpathSharedDir=")).isEqualTo(1);
    assertThat(lineContaining(workflow, "SharedMigrationUpgradePathTest"))
        .contains("-PdevpathSharedDir=${{ github.workspace }}/.ci/devpath-shared-final");
    assertThat(lineContaining(workflow, "--refresh-dependencies build"))
        .doesNotContain("devpathSharedDir");
    assertThat(lineContaining(workflow, "-Dgroups=docker"))
        .doesNotContain("devpathSharedDir");
  }

  private static String lineContaining(String value, String needle) {
    return value.lines()
        .filter(line -> line.contains(needle))
        .findFirst()
        .orElseThrow();
  }

  private static long countLine(String value, String expected) {
    return value.lines()
        .map(String::strip)
        .filter(expected::equals)
        .count();
  }

  private static int count(String value, String needle) {
    return value.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
  }
}
