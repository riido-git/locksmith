/**
 * Context models for lock and semaphore handlers.
 *
 * <p>This package contains context objects that are passed to skip handlers when lock or semaphore
 * acquisition fails. These contexts provide all relevant information about the failed acquisition
 * attempt.
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.models.LockContext} - Context information passed to lock skip
 *       handlers
 *   <li>{@link in.riido.locksmith.models.SemaphoreContext} - Context information passed to
 *       semaphore skip handlers
 * </ul>
 *
 * @author Garvit Joshi
 * @since 2.0.0
 * @see in.riido.locksmith.models.LockContext
 * @see in.riido.locksmith.models.SemaphoreContext
 */
package in.riido.locksmith.models;
