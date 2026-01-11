/**
 * Locksmith - A Spring Boot starter for Redis-based distributed locking and semaphores.
 *
 * <p>This package contains the public API for distributed locking and semaphores:
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.DistributedLock} - The main annotation for locking methods
 *   <li>{@link in.riido.locksmith.DistributedSemaphore} - The annotation for permit-based
 *       concurrency control
 *   <li>{@link in.riido.locksmith.AcquisitionMode} - Lock and semaphore acquisition strategies
 *   <li>{@link in.riido.locksmith.LockType} - Types of locks (reentrant, read, write)
 *   <li>{@link in.riido.locksmith.LeaseExpirationBehavior} - Behavior when execution exceeds lease
 *       time
 *   <li>{@link in.riido.locksmith.handler.LockSkipHandler} - Custom lock skip handler interface
 *   <li>{@link in.riido.locksmith.handler.SemaphoreSkipHandler} - Custom semaphore skip handler
 *       interface
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
 *
 *     @DistributedSemaphore(key = "api-pool", permits = 5)
 *     public void rateLimitedTask() {
 *         // Up to 5 instances can execute this concurrently
 *     }
 * }
 * }</pre>
 *
 * @author Garvit Joshi
 * @since 1.0.0
 * @see in.riido.locksmith.DistributedLock
 * @see in.riido.locksmith.DistributedSemaphore
 */
package in.riido.locksmith;
