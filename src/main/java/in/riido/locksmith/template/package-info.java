/**
 * Programmatic API for distributed locks and semaphores.
 *
 * <p>This package provides template classes for programmatic access to distributed locks and
 * semaphores, complementing the annotation-based approach provided by {@link
 * in.riido.locksmith.DistributedLock} and {@link in.riido.locksmith.DistributedSemaphore}.
 *
 * <p>Key classes:
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.template.LocksmithLockTemplate} - Template for distributed lock
 *       operations
 *   <li>{@link in.riido.locksmith.template.LocksmithSemaphoreTemplate} - Template for distributed
 *       semaphore operations
 *   <li>{@link in.riido.locksmith.template.LockCallback} - Callback interface for lock execution
 *   <li>{@link in.riido.locksmith.template.SemaphoreCallback} - Callback interface for semaphore
 *       execution
 * </ul>
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * @Autowired
 * private LocksmithLockTemplate lockTemplate;
 *
 * // Manual lock management
 * if (lockTemplate.tryLock("my-key")) {
 *     try {
 *         // Critical section
 *     } finally {
 *         lockTemplate.unlock("my-key");
 *     }
 * }
 *
 * // Callback-based execution
 * String result = lockTemplate.executeWithLock("my-key", () -> {
 *     return "executed within lock";
 * });
 * }</pre>
 *
 * @author Garvit Joshi
 * @since 2.1.0
 */
package in.riido.locksmith.template;
