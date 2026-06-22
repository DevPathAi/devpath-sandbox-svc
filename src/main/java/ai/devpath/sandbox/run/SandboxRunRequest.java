package ai.devpath.sandbox.run;

public record SandboxRunRequest(
    String code,
    String language,
    Long contentId,
    Long codeBlockId
) {
}
