/**
 * Exception classes for Locksmith distributed locking and semaphores.
 *
 * <p>This package contains exceptions that may be thrown during lock and semaphore operations:
 *
 * <p><b>Lock Exceptions:</b>
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.exception.LockNotAcquiredException} - Thrown when a lock cannot
 *       be acquired and {@link in.riido.locksmith.handler.lock.LockThrowExceptionHandler} is used
 *   <li>{@link in.riido.locksmith.exception.LeaseExpiredException} - Thrown when method execution
 *       exceeds the configured lease time
 * </ul>
 *
 * <p><b>Semaphore Exceptions:</b>
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.exception.SemaphoreNotAcquiredException} - Thrown when a permit
 *       cannot be acquired and {@link
 *       in.riido.locksmith.handler.semaphore.SemaphoreThrowExceptionHandler} is used
 *   <li>{@link in.riido.locksmith.exception.SemaphoreLeaseExpiredException} - Thrown when method
 *       execution exceeds the configured permit lease time
 *   <li>{@link in.riido.locksmith.exception.SemaphoreConfigurationException} - Thrown when
 *       semaphore configuration is invalid (e.g., same key with different permits)
 * </ul>
 *
 * @author Garvit Joshi
 * @since 1.0.0
 * @see in.riido.locksmith.exception.LockNotAcquiredException
 * @see in.riido.locksmith.exception.SemaphoreNotAcquiredException
 */
package in.riido.locksmith.exception;
