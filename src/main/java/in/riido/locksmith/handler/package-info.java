/**
 * Handler interfaces and implementations for lock and semaphore acquisition failures.
 *
 * <p>This package provides a pluggable mechanism for handling cases when a distributed lock or
 * semaphore permit cannot be acquired. The key components are:
 *
 * <p><b>Lock Handlers:</b>
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.handler.LockSkipHandler} - The interface for custom lock handlers
 *   <li>{@link in.riido.locksmith.models.LockContext} - Context information passed to lock handlers
 *   <li>{@link in.riido.locksmith.handler.lock.LockThrowExceptionHandler} - Default lock handler
 *       that throws exceptions
 *   <li>{@link in.riido.locksmith.handler.lock.LockReturnDefaultHandler} - Lock handler that
 *       returns default values
 * </ul>
 *
 * <p><b>Semaphore Handlers:</b>
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.handler.SemaphoreSkipHandler} - The interface for custom
 *       semaphore handlers
 *   <li>{@link in.riido.locksmith.models.SemaphoreContext} - Context information passed to
 *       semaphore handlers
 *   <li>{@link in.riido.locksmith.handler.semaphore.SemaphoreThrowExceptionHandler} - Default
 *       semaphore handler that throws exceptions
 *   <li>{@link in.riido.locksmith.handler.semaphore.SemaphoreReturnDefaultHandler} - Semaphore
 *       handler that returns default values
 * </ul>
 *
 * @author Garvit Joshi
 * @since 1.2.0
 * @see in.riido.locksmith.handler.LockSkipHandler
 * @see in.riido.locksmith.handler.SemaphoreSkipHandler
 */
package in.riido.locksmith.handler;
