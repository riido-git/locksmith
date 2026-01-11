/**
 * AOP aspect implementations for distributed locking and semaphores.
 *
 * <p>This package contains the AspectJ aspects that intercept methods annotated with {@link
 * in.riido.locksmith.DistributedLock} and {@link in.riido.locksmith.DistributedSemaphore} and
 * handle the lock/permit acquisition/release lifecycle.
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.aspect.DistributedLockAspect} - Handles distributed lock
 *       lifecycle
 *   <li>{@link in.riido.locksmith.aspect.DistributedSemaphoreAspect} - Handles distributed
 *       semaphore permit lifecycle
 * </ul>
 *
 * <p>The aspects are automatically registered by the autoconfiguration when a {@link
 * org.redisson.api.RedissonClient} bean is available.
 *
 * @author Garvit Joshi
 * @since 1.0.0
 * @see in.riido.locksmith.aspect.DistributedLockAspect
 * @see in.riido.locksmith.aspect.DistributedSemaphoreAspect
 */
package in.riido.locksmith.aspect;
