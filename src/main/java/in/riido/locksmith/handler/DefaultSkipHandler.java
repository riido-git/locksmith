package in.riido.locksmith.handler;

/**
 * The default {@link LockSkipHandler} that indicates no custom handler is specified.
 *
 * <p>This handler is used as a marker to indicate that the {@link in.riido.locksmith.SkipBehavior}
 * enum should be used instead. When this handler is specified (which is the default), the aspect
 * will fall back to the behavior defined by {@code onSkip}.
 *
 * <p>This class should not be used directly. It exists only to provide a default value for the
 * {@code skipHandler} annotation parameter.
 *
 * @author Garvit Joshi
 * @since 1.3.0
 */
public final class DefaultSkipHandler implements LockSkipHandler {

  @Override
  public Object handle(LockContext context) {
    throw new UnsupportedOperationException(
        "DefaultSkipHandler should not be invoked directly. "
            + "Use a concrete handler or rely on SkipBehavior enum.");
  }
}
