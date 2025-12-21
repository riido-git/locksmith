package in.riido.locksmith;

/**
 * Defines the type of lock to acquire for distributed locking.
 *
 * @author Garvit Joshi
 * @since 1.2.0
 */
public enum LockType {

  /**
   * A reentrant mutual exclusion lock. Only one thread/instance can hold the lock at a time. This
   * is the default lock type.
   */
  REENTRANT,

  /**
   * A shared read lock. Multiple threads/instances can hold the read lock simultaneously, as long
   * as no thread holds the write lock. Use this for read-heavy operations where concurrent reads
   * are safe.
   */
  READ,

  /**
   * An exclusive write lock. Only one thread/instance can hold the write lock, and no read locks
   * can be held simultaneously. Use this for write operations that require exclusive access.
   */
  WRITE
}
