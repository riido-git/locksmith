/**
 * Built-in semaphore skip handler implementations.
 *
 * <p>This package contains the default implementations of {@link
 * in.riido.locksmith.handler.SemaphoreSkipHandler} that are used when semaphore permit acquisition
 * fails:
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.handler.semaphore.SemaphoreThrowExceptionHandler} - Default
 *       handler that throws {@link in.riido.locksmith.exception.SemaphoreNotAcquiredException}
 *   <li>{@link in.riido.locksmith.handler.semaphore.SemaphoreReturnDefaultHandler} - Handler that
 *       returns null for objects, default values for primitives
 * </ul>
 *
 * @author Garvit Joshi
 * @since 2.0.0
 * @see in.riido.locksmith.handler.SemaphoreSkipHandler
 * @see in.riido.locksmith.handler.semaphore.SemaphoreThrowExceptionHandler
 * @see in.riido.locksmith.handler.semaphore.SemaphoreReturnDefaultHandler
 */
package in.riido.locksmith.handler.semaphore;
