package ai.devpath.sandbox.run;

import ai.devpath.shared.error.ApiException;
import ai.devpath.shared.error.ErrorCode;

/** A run was not accepted because the bounded Sandbox capacity is already reserved. */
public class SandboxBusyException extends ApiException {

  public SandboxBusyException(String message) {
    super(ErrorCode.SANDBOX_BUSY, message);
  }
}
