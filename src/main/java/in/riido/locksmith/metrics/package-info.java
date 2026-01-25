/**
 * Micrometer metrics support for Locksmith distributed locking and semaphores.
 *
 * <p>This package provides optional Micrometer integration for observability of lock and semaphore
 * operations. Metrics are only recorded when:
 *
 * <ul>
 *   <li>{@code micrometer-core} is on the classpath
 *   <li>A {@link io.micrometer.core.instrument.MeterRegistry} bean exists
 *   <li>The respective {@code metrics-enabled} property is set to {@code true}
 * </ul>
 *
 * <h2>Configuration</h2>
 *
 * <pre>{@code
 * locksmith:
 *   lock:
 *     metrics-enabled: true
 *   semaphore:
 *     metrics-enabled: true
 * }</pre>
 *
 * <h2>Available Metrics</h2>
 *
 * <h3>Lock Metrics</h3>
 *
 * <ul>
 *   <li>{@code locksmith.lock.acquired} - Counter for successful lock acquisitions
 *   <li>{@code locksmith.lock.skipped} - Counter for skipped acquisitions (tagged by reason)
 *   <li>{@code locksmith.lock.lease.expired} - Counter for lease expirations
 *   <li>{@code locksmith.lock.acquisition.time} - Timer for lock acquisition duration
 *   <li>{@code locksmith.lock.held.time} - Timer for lock held duration
 *   <li>{@code locksmith.lock.autorenew.active} - Gauge for active auto-renewed locks
 * </ul>
 *
 * <h3>Semaphore Metrics</h3>
 *
 * <ul>
 *   <li>{@code locksmith.semaphore.acquired} - Counter for successful permit acquisitions
 *   <li>{@code locksmith.semaphore.skipped} - Counter for skipped acquisitions (tagged by reason)
 *   <li>{@code locksmith.semaphore.lease.expired} - Counter for lease expirations
 *   <li>{@code locksmith.semaphore.acquisition.time} - Timer for permit acquisition duration
 *   <li>{@code locksmith.semaphore.held.time} - Timer for permit held duration
 * </ul>
 *
 * @author Garvit Joshi
 * @since 2.1.0
 * @see in.riido.locksmith.metrics.LockMetrics
 * @see in.riido.locksmith.metrics.SemaphoreMetrics
 */
@org.jspecify.annotations.NullMarked
package in.riido.locksmith.metrics;
