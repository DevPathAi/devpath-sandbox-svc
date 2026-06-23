package ai.devpath.sandbox.run;

public record SandboxSessionView(
    Long id, Long userId, String language, Long contentId,
    String submittedCode, String stdout, String stderr, Integer exitCode, String status) {

  static SandboxSessionView from(SandboxSession s) {
    return new SandboxSessionView(
        s.getId(), s.getUserId(), s.getLanguage(), s.getContentId(),
        s.getSubmittedCode(), s.getStdout(), s.getStderr(), s.getExitCode(), s.getStatus());
  }
}
