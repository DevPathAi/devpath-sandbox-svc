package ai.devpath.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** CI-only proof that a database carrying the originally published checksum upgrades in place. */
class SharedMigrationUpgradePathTest {

  @Test
  @EnabledIfEnvironmentVariable(
      named = "HISTORICAL_SHARED_MIGRATIONS",
      matches = ".+")
  void validatesPublishedHistoryThenMigratesAndValidatesTheFinalSharedLineage() {
    Path historical = Path.of(System.getenv("HISTORICAL_SHARED_MIGRATIONS"))
        .toAbsolutePath().normalize();
    Path et8 = Path.of(System.getenv("ET8_SHARED_MIGRATIONS"))
        .toAbsolutePath().normalize();
    assertThat(Files.isDirectory(historical)).isTrue();
    assertThat(Files.isDirectory(et8)).isTrue();
    String url = System.getenv().getOrDefault(
        "DB_URL", "jdbc:postgresql://localhost:5432/devpath");
    String user = System.getenv().getOrDefault("DB_USER", "devpath");
    String password = System.getenv().getOrDefault("DB_PASSWORD", "localdev");

    Flyway published = Flyway.configure()
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .dataSource(url, user, password)
        .locations("filesystem:" + historical.toString().replace('\\', '/'))
        .placeholderReplacement(false)
        .load();
    published.migrate();
    assertThat(published.validateWithResult().validationSuccessful).isTrue();

    Flyway exactEt8 = Flyway.configure()
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .dataSource(url, user, password)
        .locations("filesystem:" + et8.toString().replace('\\', '/'))
        .placeholderReplacement(false)
        .load();
    exactEt8.migrate();
    assertThat(exactEt8.validateWithResult().validationSuccessful).isTrue();
    assertThat(exactEt8.info().current().getVersion().getVersion())
        .isEqualTo("202608161008");

    Flyway latest = Flyway.configure()
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .placeholderReplacement(false)
        .load();
    latest.migrate();
    assertThat(latest.validateWithResult().validationSuccessful).isTrue();
    assertThat(latest.info().current().getVersion().getVersion())
        .isEqualTo(System.getenv("FINAL_SHARED_VERSION"));
  }
}
