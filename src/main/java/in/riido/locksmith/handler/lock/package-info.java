/**
 * Built-in lock skip handler implementations.
 *
 * <p>This package contains the default implementations of {@link
 * in.riido.locksmith.handler.LockSkipHandler} that are used when lock acquisition fails:
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.handler.lock.LockThrowExceptionHandler} - Default handler that
 *       throws {@link in.riido.locksmith.exception.LockNotAcquiredException}
 *   <li>{@link in.riido.locksmith.handler.lock.LockReturnDefaultHandler} - Handler that returns
 *       null for objects, default values for primitives
 * </ul>
 *
 * @author Garvit Joshi
 * @since 1.2.0
 * @see in.riido.locksmith.handler.LockSkipHandler
 * @see in.riido.locksmith.handler.lock.LockThrowExceptionHandler
 * @see in.riido.locksmith.handler.lock.LockReturnDefaultHandler
 */
package in.riido.locksmith.handler.lock;
