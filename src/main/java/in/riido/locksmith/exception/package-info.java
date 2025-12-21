/**
 * Exception classes for Locksmith distributed locking.
 *
 * <p>This package contains exceptions that may be thrown during lock operations:
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.exception.LockNotAcquiredException} - Thrown when a lock cannot
 *       be acquired and {@link in.riido.locksmith.handler.ThrowExceptionHandler} is used
 *   <li>{@link in.riido.locksmith.exception.LeaseExpiredException} - Thrown when method execution
 *       exceeds the configured lease time
 * </ul>
 *
 * @author Garvit Joshi
 * @since 1.0.0
 * @see in.riido.locksmith.exception.LockNotAcquiredException
 */
package in.riido.locksmith.exception;
