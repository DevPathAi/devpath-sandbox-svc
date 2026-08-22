package ai.devpath.sandbox.run;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Runner endpoint and isolation policy. Production enables the fail-closed checks. */
@Component
public final class SandboxRunnerProperties {

  private final String endpoint;
  private final String runtime;
  private final boolean requireIsolation;
  private final boolean tlsVerify;
  private final String certPath;

  @Autowired
  public SandboxRunnerProperties(
      @Value("${devpath.sandbox.runner.endpoint:}") String endpoint,
      @Value("${devpath.sandbox.runner.runtime:}") String runtime,
      @Value("${devpath.sandbox.runner.require-isolation:false}") boolean requireIsolation,
      @Value("${devpath.sandbox.runner.tls-verify:false}") boolean tlsVerify,
      @Value("${devpath.sandbox.runner.cert-path:}") String certPath) {
    this.endpoint = endpoint == null ? "" : endpoint.trim();
    this.runtime = runtime == null ? "" : runtime.trim();
    this.requireIsolation = requireIsolation;
    this.tlsVerify = tlsVerify;
    this.certPath = certPath == null ? "" : certPath.trim();
  }

  static SandboxRunnerProperties development() {
    return new SandboxRunnerProperties("", "", false, false, "");
  }

  public void assertSecure() {
    if (!requireIsolation) {
      return;
    }
    String normalized = endpoint.toLowerCase(java.util.Locale.ROOT);
    if (!normalized.startsWith("tcp://") && !normalized.startsWith("https://")) {
      throw new IllegalStateException("Production Sandbox requires a dedicated remote runner");
    }
    if (!"runsc".equals(runtime)) {
      throw new IllegalStateException("Production Sandbox requires the runsc runtime");
    }
    if (!tlsVerify || certPath.isBlank()) {
      throw new IllegalStateException("Production Sandbox runner requires verified mTLS");
    }
  }

  String endpoint() { return endpoint; }
  String runtime() { return runtime; }
  boolean tlsVerify() { return tlsVerify; }
  String certPath() { return certPath; }
}
