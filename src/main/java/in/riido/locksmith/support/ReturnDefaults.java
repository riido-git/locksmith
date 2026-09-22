package in.riido.locksmith.support;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** The value an annotated method returns when its failure policy is {@code SKIP}. */
public final class ReturnDefaults {

  private static final Map<Class<?>, Object> DEFAULTS =
      Map.ofEntries(
          Map.entry(Optional.class, Optional.empty()),
          Map.entry(boolean.class, false),
          Map.entry(Boolean.class, false),
          Map.entry(byte.class, (byte) 0),
          Map.entry(Byte.class, (byte) 0),
          Map.entry(short.class, (short) 0),
          Map.entry(Short.class, (short) 0),
          Map.entry(int.class, 0),
          Map.entry(Integer.class, 0),
          Map.entry(long.class, 0L),
          Map.entry(Long.class, 0L),
          Map.entry(float.class, 0F),
          Map.entry(Float.class, 0F),
          Map.entry(double.class, 0D),
          Map.entry(Double.class, 0D),
          Map.entry(char.class, '\0'),
          Map.entry(Character.class, '\0'));

  private ReturnDefaults() {}

  /**
   * Returns the default value for a method return type: a new future already completed with {@code
   * null} for {@code CompletionStage} and its subtypes, {@code Optional.empty()} for {@code
   * Optional}, {@code false} for booleans, zero of the type for the other primitives and their
   * boxes, and {@code null} for {@code void} and every other reference type.
   *
   * @param returnType the method return type
   * @return the default value, or null
   */
  public static @Nullable Object forType(@NonNull Class<?> returnType) {
    if (CompletionStage.class.isAssignableFrom(returnType)) {
      return CompletableFuture.completedFuture(null);
    }
    return DEFAULTS.get(returnType);
  }
}
