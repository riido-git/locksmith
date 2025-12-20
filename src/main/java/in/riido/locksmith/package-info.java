/**
 * Locksmith - A Spring Boot starter for Redis-based distributed locking.
 *
 * <p>This package contains the public API for distributed locking:
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.DistributedLock} - The main annotation for locking methods
 *   <li>{@link in.riido.locksmith.LockAcquisitionMode} - Lock acquisition strategies
 *   <li>{@link in.riido.locksmith.SkipBehavior} - Behavior when lock cannot be acquired
 * </ul>
 *
 * <h2>Quick Start</h2>
 *
 * <pre>{@code
 * @Service
 * public class MyService {
 *
 *     @DistributedLock(key = "my-task")
 *     public void criticalTask() {
 *         // Only one instance executes this at a time
 *     }
 * }
 * }</pre>
 *
 * @author Garvit Joshi
 * @since 1.0.0
 * @see in.riido.locksmith.DistributedLock
 */
package in.riido.locksmith;
