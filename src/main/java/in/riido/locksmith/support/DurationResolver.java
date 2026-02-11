package in.riido.locksmith.support;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.convert.DurationStyle;

/**
 * Utility class for resolving duration strings with fallback to default values.
 *
 * <p>Supports Spring Boot's duration format styles:
 *
 * <ul>
 *   <li>Simple format: {@code "10s"}, {@code "5m"}, {@code "1h"}
 *   <li>ISO-8601 format: {@code "PT10S"}, {@code "PT5M"}, {@code "PT1H"}
 * </ul>
 *
 * <p>This class is thread-safe and stateless.
 *
 * @author Garvit Joshi
 * @since 2.0.0
 */
public final class DurationResolver {

  private DurationResolver() {
    // Utility class
  }

  /**
   * Resolves a duration from the given string, falling back to the default if blank.
   *
   * @param durationString the duration string (e.g., "10m", "30s", "PT10M")
   * @param defaultValue the default value to use if the string is blank
   * @return the resolved Duration, or the default value if the string is blank
   * @throws IllegalArgumentException if the value is not a known style or cannot be parsed
   */
  public static Duration resolve(@Nullable String durationString, Duration defaultValue) {
    if (durationString == null || durationString.isBlank()) {
      return defaultValue;
    }
    try {
      return DurationStyle.detectAndParse(durationString);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Invalid duration format: '"
              + durationString
              + "'. Expected formats: simple (e.g., '10s', '5m', '1h') "
              + "or ISO-8601 (e.g., 'PT10S', 'PT5M', 'PT1H')",
          e);
    }
  }
}
