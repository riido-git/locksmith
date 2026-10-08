package in.riido.locksmith;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Thrown when an annotation, a key template or a property is misconfigured. */
public class LocksmithConfigurationException extends LocksmithException {

  /**
   * Creates an exception with a message.
   *
   * @param message what is misconfigured and where
   */
  public LocksmithConfigurationException(@NonNull String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and a cause.
   *
   * @param message what is misconfigured and where
   * @param cause the underlying cause, or null
   */
  public LocksmithConfigurationException(@NonNull String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
