/**
 * Auto-closeable handles for template-based distributed operations.
 *
 * <p>This package contains handle types returned by template APIs when acquiring locks or semaphore
 * permits. These handles are designed for try-with-resources usage to ensure safe release in {@code
 * close()}.
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.template.handle.LockHandle} - Handle for distributed lock
 *       acquisition
 *   <li>{@link in.riido.locksmith.template.handle.PermitHandle} - Handle for semaphore permit
 *       acquisition
 * </ul>
 *
 * @author Garvit Joshi
 * @since 3.0.3
 * @see in.riido.locksmith.template.LocksmithLockTemplate
 * @see in.riido.locksmith.template.LocksmithSemaphoreTemplate
 */
package in.riido.locksmith.template.handle;
