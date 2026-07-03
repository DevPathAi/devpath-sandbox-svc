package ai.devpath.sandbox.run;

import ai.devpath.shared.error.ApiException;
import ai.devpath.shared.error.ErrorCode;

/** 샌드박스 세션 없음 → 스펙 §3.4 RESOURCE_NOT_FOUND(404). 공용 ApiExceptionHandler가 envelope로 렌더. */
public class SessionNotFoundException extends ApiException {
  public SessionNotFoundException(String message) {
    super(ErrorCode.RESOURCE_NOT_FOUND, message);
  }
}
