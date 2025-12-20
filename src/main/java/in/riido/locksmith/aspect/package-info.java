/**
 * AOP aspect implementation for distributed locking.
 *
 * <p>This package contains the AspectJ aspect that intercepts methods annotated with {@link
 * in.riido.locksmith.DistributedLock} and handles the lock acquisition/release lifecycle.
 *
 * <p>The aspect is automatically registered by the auto-configuration when a {@link
 * org.redisson.api.RedissonClient} bean is available.
 *
 * @author Garvit Joshi
 * @since 1.0.0
 * @see in.riido.locksmith.aspect.DistributedLockAspect
 */
package in.riido.locksmith.aspect;
