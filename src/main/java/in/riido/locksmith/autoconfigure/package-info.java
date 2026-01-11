/**
 * Spring Boot auto-configuration for Locksmith.
 *
 * <p>This package contains:
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.autoconfigure.LocksmithAutoConfiguration} - Auto-configures the
 *       distributed lock and semaphore aspects
 *   <li>{@link in.riido.locksmith.autoconfigure.LocksmithProperties} - Configuration properties
 *       with prefix {@code locksmith.*}
 * </ul>
 *
 * <h2>Configuration Properties</h2>
 *
 * <pre>{@code
 * locksmith:
 *   lock:
 *     lease-time: 10m
 *     wait-time: 60s
 *     key-prefix: "lock:"
 *     debug: false
 *   semaphore:
 *     lease-time: 5m
 *     wait-time: 60s
 *     key-prefix: "semaphore:"
 *     debug: false
 * }</pre>
 *
 * @author Garvit Joshi
 * @since 1.0.0
 * @see in.riido.locksmith.autoconfigure.LocksmithAutoConfiguration
 * @see in.riido.locksmith.autoconfigure.LocksmithProperties
 */
package in.riido.locksmith.autoconfigure;
