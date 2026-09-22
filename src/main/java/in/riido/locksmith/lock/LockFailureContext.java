package in.riido.locksmith.lock;

import java.lang.reflect.Method;
import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Describes a call whose lock was not acquired; passed to {@link LockFailureHandler}.
 *
 * @param key the full Redis key, including the prefix
 * @param method the annotated method
 * @param args the arguments of the call; the array is never null, its elements may be
 * @param waitTime how long the call waited for the lock
 */
public record LockFailureContext(
    @NonNull String key,
    @NonNull Method method,
    @Nullable Object @NonNull [] args,
    @NonNull Duration waitTime) {}
