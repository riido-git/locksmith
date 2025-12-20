/**
 * Exception classes for Locksmith distributed locking.
 *
 * <p>This package contains exceptions that may be thrown during lock operations:
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.exception.LockNotAcquiredException} - Thrown when a lock cannot
 *       be acquired and {@link in.riido.locksmith.SkipBehavior#THROW_EXCEPTION} is configured
 * </ul>
 *
 * @author Garvit Joshi
 * @since 1.0.0
 * @see in.riido.locksmith.exception.LockNotAcquiredException
 */
package in.riido.locksmith.exception;
