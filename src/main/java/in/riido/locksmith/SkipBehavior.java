package in.riido.locksmith;

import in.riido.locksmith.exception.LockNotAcquiredException;

/**
 * Defines the behavior when a distributed lock cannot be acquired and the method execution is
 * skipped.
 *
 * @author Garvit Joshi
 * @since 1.0.0
 */
public enum SkipBehavior {

  /**
   * Throw a {@link LockNotAcquiredException} when the lock cannot be acquired. This is the default
   * behavior, making lock acquisition failures explicit to the caller.
   */
  THROW_EXCEPTION,

  /**
   * Return {@code null} for object return types, or the default value for primitive types (0 for
   * numeric types, false for boolean, '\u0000' for char). Use this for fire-and-forget scenarios
   * like scheduled tasks where skipping is acceptable.
   */
  RETURN_DEFAULT
}
