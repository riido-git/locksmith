package kotlin.coroutines;

/**
 * Stand-in for Kotlin's {@code Continuation}, the hidden last parameter of a compiled suspend
 * function. Locksmith matches it by name, so tests need no Kotlin dependency.
 *
 * @param <T> the result type
 */
public interface Continuation<T> {}
