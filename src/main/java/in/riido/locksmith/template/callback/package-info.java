/**
 * Callback interfaces for template-based distributed operations.
 *
 * <p>This package contains functional interfaces used by template APIs to execute user code within
 * acquired coordination primitives.
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.template.callback.LockCallback} - Callback for execution within a
 *       distributed lock
 *   <li>{@link in.riido.locksmith.template.callback.SemaphoreCallback} - Callback for execution
 *       while holding a semaphore permit
 *   <li>{@link in.riido.locksmith.template.callback.RateLimitCallback} - Callback for execution
 *       after rate limit permit acquisition
 * </ul>
 *
 * @author Garvit Joshi
 * @since 2.1.0
 * @see in.riido.locksmith.template.LocksmithLockTemplate
 * @see in.riido.locksmith.template.LocksmithSemaphoreTemplate
 * @see in.riido.locksmith.template.LocksmithRateLimitTemplate
 */
package in.riido.locksmith.template.callback;
