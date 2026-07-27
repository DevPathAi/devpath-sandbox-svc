package ai.devpath.sandbox.run;

import ai.devpath.shared.error.ApiException;
import ai.devpath.shared.error.ErrorCode;

/** 샌드박스 러너 불가 → 스펙 §3.4 SANDBOX_UNAVAILABLE(503). 공용 ApiExceptionHandler가 envelope로 렌더. */
public class SandboxUnavailableException extends ApiException {

  public SandboxUnavailableException(String message) {
    super(ErrorCode.SANDBOX_UNAVAILABLE, message);
  }

  public SandboxUnavailableException(String message, Throwable cause) {
    super(ErrorCode.SANDBOX_UNAVAILABLE, message);
    initCause(cause);
  }
}
