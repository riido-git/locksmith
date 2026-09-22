package in.riido.locksmith.semaphore;

import java.lang.reflect.Method;
import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Describes a call whose semaphore permit was not acquired; passed to {@link
 * SemaphoreFailureHandler}.
 *
 * @param key the full Redis key, including the prefix
 * @param permits the permit count configured for the semaphore
 * @param method the annotated method
 * @param args the arguments of the call; the array is never null, its elements may be
 * @param waitTime how long the call waited for a permit
 */
public record SemaphoreFailureContext(
    @NonNull String key,
    int permits,
    @NonNull Method method,
    @Nullable Object @NonNull [] args,
    @NonNull Duration waitTime) {}
