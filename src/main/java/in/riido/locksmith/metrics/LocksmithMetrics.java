package in.riido.locksmith.metrics;

import java.time.Duration;
import org.jspecify.annotations.NonNull;

/** Records acquire attempts and hold times of locks and semaphore permits. */
public interface LocksmithMetrics {

  /**
   * Records one acquire attempt.
   *
   * @param primitive the primitive that was acquired
   * @param outcome how the attempt ended
   * @param waited time spent waiting
   */
  void recordAcquire(
      @NonNull Primitive primitive, @NonNull Outcome outcome, @NonNull Duration waited);

  /**
   * Records the time from acquire to release.
   *
   * @param primitive the primitive that was held
   * @param held time held
   */
  void recordHeld(@NonNull Primitive primitive, @NonNull Duration held);

  /** The coordination primitive a measurement belongs to. */
  enum Primitive {
    /** A distributed lock of any type. */
    LOCK("lock"),
    /** A semaphore permit. */
    SEMAPHORE("semaphore");

    private final @NonNull String tagValue;

    Primitive(@NonNull String tagValue) {
      this.tagValue = tagValue;
    }

    /**
     * Returns the value of the {@code primitive} tag.
     *
     * @return the tag value
     */
    public @NonNull String tagValue() {
      return tagValue;
    }
  }

  /** How an acquire attempt ended. */
  enum Outcome {
    /** The lock or permit was acquired. */
    ACQUIRED("acquired"),
    /** The wait time ran out without acquiring. */
    SKIPPED("skipped"),
    /** The waiting thread was interrupted. */
    INTERRUPTED("interrupted");

    private final @NonNull String tagValue;

    Outcome(@NonNull String tagValue) {
      this.tagValue = tagValue;
    }

    /**
     * Returns the value of the {@code outcome} tag.
     *
     * @return the tag value
     */
    public @NonNull String tagValue() {
      return tagValue;
    }
  }
}
