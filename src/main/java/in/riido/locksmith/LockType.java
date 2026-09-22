package in.riido.locksmith;

/** The kind of distributed lock taken for a key. */
public enum LockType {
  /** Exclusive lock that the holding thread may acquire again. */
  REENTRANT,
  /** Shared lock; any number of readers may hold it while no writer does. */
  READ,
  /** Exclusive lock that excludes readers and other writers of the same key. */
  WRITE
}
