/**
 * Support utilities for the Locksmith library.
 *
 * <p>This package contains shared utility classes used by both distributed lock and semaphore
 * implementations:
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.support.DurationResolver} - Resolves duration strings to {@link
 *       java.time.Duration} objects
 *   <li>{@link in.riido.locksmith.support.SpELKeyResolver} - Resolves SpEL expressions in lock and
 *       semaphore keys
 * </ul>
 *
 * @author Garvit Joshi
 * @since 1.0.0
 * @see in.riido.locksmith.support.DurationResolver
 * @see in.riido.locksmith.support.SpELKeyResolver
 */
package in.riido.locksmith.support;
