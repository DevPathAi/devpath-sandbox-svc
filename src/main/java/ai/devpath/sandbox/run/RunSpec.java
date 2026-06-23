package ai.devpath.sandbox.run;

public record RunSpec(
    String code,
    String language,
    long sandboxSessionId
) {
}
