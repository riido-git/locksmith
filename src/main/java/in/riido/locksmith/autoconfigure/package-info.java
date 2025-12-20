/**
 * Spring Boot auto-configuration for Locksmith.
 *
 * <p>This package contains:
 *
 * <ul>
 *   <li>{@link in.riido.locksmith.autoconfigure.LocksmithAutoConfiguration} - Auto-configures the
 *       distributed lock aspect
 *   <li>{@link in.riido.locksmith.autoconfigure.LocksmithProperties} - Configuration properties
 *       with prefix {@code locksmith.*}
 * </ul>
 *
 * <h2>Configuration Properties</h2>
 *
 * <pre>{@code
 * locksmith.lease-time-minutes=10
 * locksmith.wait-time-seconds=60
 * locksmith.key-prefix=lock:
 * }</pre>
 *
 * @author Garvit Joshi
 * @since 1.0.0
 * @see in.riido.locksmith.autoconfigure.LocksmithAutoConfiguration
 * @see in.riido.locksmith.autoconfigure.LocksmithProperties
 */
package in.riido.locksmith.autoconfigure;
