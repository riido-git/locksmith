package in.riido.locksmith;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Base class of every exception Locksmith throws. Unchecked. */
public abstract class LocksmithException extends RuntimeException {

  /**
   * Creates an exception with a message.
   *
   * @param message the detail message
   */
  protected LocksmithException(@NonNull String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and a cause.
   *
   * @param message the detail message
   * @param cause the underlying cause, or null
   */
  protected LocksmithException(@NonNull String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
