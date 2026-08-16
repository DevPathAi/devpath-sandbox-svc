package ai.devpath.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import ai.devpath.shared.error.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ImmutableSharedResolutionContractTest {

  private static final String VERSION = "0.0.1-et9.20260816";
  private static final String SHA_256 =
      "94e2adb769790d813a872163347ede20ad4c75ae88e5811df2ec6625a340f21f";

  @Test
  void resolvesTheExactImmutableSharedReleaseWithoutCompositeSubstitution() throws Exception {
    Path source =
        Path.of(ErrorCode.class.getProtectionDomain().getCodeSource().getLocation().toURI());

    assertThat(source).isRegularFile().hasFileName("devpath-shared-" + VERSION + ".jar");
    assertThat(source.toString().replace('\\', '/')).doesNotContain("/shared-et9/build/");
    assertThat(sha256(source)).isEqualTo(SHA_256);
    assertThat(Files.readString(Path.of("gradle.properties")))
        .contains("devpathSharedVersion=" + VERSION)
        .doesNotContain("devpathSharedVersion=0.0.1-SNAPSHOT");
    assertThat(Files.readString(Path.of("build.gradle.kts")))
        .contains("implementation(devpathSharedCoordinate)")
        .doesNotContain("devpath-shared:0.0.1-SNAPSHOT")
        .doesNotContain("devpath-shared:0.0.1-et8.20260816");
  }

  private static String sha256(Path file) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }
}
