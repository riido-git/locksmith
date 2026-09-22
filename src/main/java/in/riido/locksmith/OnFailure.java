package in.riido.locksmith;

/** What an annotated method does when its lock or permit is not acquired. */
public enum OnFailure {
  /** Throw the primitive's not-acquired exception. */
  THROW,
  /** Skip the method and return the default value of its return type. */
  SKIP,
  /** Skip the method and return the value of the configured failure handler bean. */
  HANDLER
}
