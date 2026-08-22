package ai.devpath.sandbox.run;

public record SandboxSessionView(
    Long id, Long userId, String language, Long contentId, Long codeBlockId,
    String submittedCode, String stdout, String stderr, Integer exitCode, String status,
    boolean outputTruncated) {

  static SandboxSessionView from(SandboxSession s) {
    return new SandboxSessionView(
        s.getId(), s.getUserId(), s.getLanguage(), s.getContentId(), s.getCodeBlockId(),
        s.getSubmittedCode(), s.getStdout(), s.getStderr(), s.getExitCode(), s.getStatus(),
        s.isOutputTruncated());
  }
}
