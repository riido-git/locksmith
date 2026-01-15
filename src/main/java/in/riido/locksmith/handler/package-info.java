/**
 * Handler interfaces and implementations for lock and semaphore acquisition failures.
 *
 * <p>This package provides a pluggable mechanism for handling cases when a distributed lock or
 * semaphore permit cannot be acquired. The key components are:
 *
 * <p><b>Handler Interfaces:</b>
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.handler.LockSkipHandler} - Interface for custom lock handlers
 *   <li>{@link in.riido.locksmith.handler.SemaphoreSkipHandler} - Interface for custom semaphore
 *       handlers
 * </ul>
 *
 * <p><b>Lock Handler Implementations:</b> (in {@code handler.lock} subpackage)
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.handler.lock.LockThrowExceptionHandler} - Default handler that
 *       throws exceptions
 *   <li>{@link in.riido.locksmith.handler.lock.LockReturnDefaultHandler} - Handler that returns
 *       default values
 * </ul>
 *
 * <p><b>Semaphore Handler Implementations:</b> (in {@code handler.semaphore} subpackage)
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.handler.semaphore.SemaphoreThrowExceptionHandler} - Default
 *       handler that throws exceptions
 *   <li>{@link in.riido.locksmith.handler.semaphore.SemaphoreReturnDefaultHandler} - Handler that
 *       returns default values
 * </ul>
 *
 * <p><b>Utilities:</b>
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.handler.DefaultValueResolver} - Shared utility for resolving
 *       default values based on return types
 * </ul>
 *
 * @author Garvit Joshi
 * @since 1.2.0
 * @see in.riido.locksmith.handler.LockSkipHandler
 * @see in.riido.locksmith.handler.SemaphoreSkipHandler
 * @see in.riido.locksmith.handler.DefaultValueResolver
 */
package in.riido.locksmith.handler;
