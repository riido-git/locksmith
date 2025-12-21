/**
 * Handler interfaces and implementations for lock acquisition failures.
 *
 * <p>This package provides a pluggable mechanism for handling cases when a distributed lock cannot
 * be acquired. The key components are:
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.handler.LockSkipHandler} - The interface for custom handlers
 *   <li>{@link in.riido.locksmith.handler.LockContext} - Context information passed to handlers
 *   <li>{@link in.riido.locksmith.handler.ThrowExceptionHandler} - Default handler that throws
 *       exceptions
 *   <li>{@link in.riido.locksmith.handler.ReturnDefaultHandler} - Handler that returns default
 *       values
 * </ul>
 *
 * @author Garvit Joshi
 * @since 1.0.2
 * @see in.riido.locksmith.handler.LockSkipHandler
 */
package in.riido.locksmith.handler;
