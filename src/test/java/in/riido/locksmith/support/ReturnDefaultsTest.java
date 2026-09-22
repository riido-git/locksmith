package in.riido.locksmith.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("ReturnDefaults")
class ReturnDefaultsTest {

  @Nested
  @DisplayName("forType")
  class ForType {

    @Test
    @DisplayName("void returns null")
    void voidReturnsNull() {
      assertThat(ReturnDefaults.forType(void.class)).isNull();
    }

    @Test
    @DisplayName("Optional returns Optional.empty()")
    void optionalReturnsEmpty() {
      assertThat(ReturnDefaults.forType(Optional.class)).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("boolean and Boolean return false")
    void booleansReturnFalse() {
      assertThat(ReturnDefaults.forType(boolean.class)).isEqualTo(false);
      assertThat(ReturnDefaults.forType(Boolean.class)).isEqualTo(false);
    }

    static Stream<Arguments> numericTypes() {
      return Stream.of(
          Arguments.of(byte.class, (byte) 0),
          Arguments.of(Byte.class, (byte) 0),
          Arguments.of(short.class, (short) 0),
          Arguments.of(Short.class, (short) 0),
          Arguments.of(int.class, 0),
          Arguments.of(Integer.class, 0),
          Arguments.of(long.class, 0L),
          Arguments.of(Long.class, 0L),
          Arguments.of(float.class, 0F),
          Arguments.of(Float.class, 0F),
          Arguments.of(double.class, 0D),
          Arguments.of(Double.class, 0D),
          Arguments.of(char.class, '\0'),
          Arguments.of(Character.class, '\0'));
    }

    @ParameterizedTest(name = "{0} returns zero of that type")
    @MethodSource("numericTypes")
    @DisplayName("primitives and boxes return zero of that type")
    void primitivesReturnZeroOfType(Class<?> type, Object zero) {
      assertThat(ReturnDefaults.forType(type)).isEqualTo(zero).isInstanceOf(zero.getClass());
    }

    @Test
    @DisplayName("CompletionStage and CompletableFuture return a new future completed with null")
    void stagesReturnCompletedNullFuture() {
      Object stage = ReturnDefaults.forType(CompletionStage.class);
      Object future = ReturnDefaults.forType(CompletableFuture.class);

      assertThat(stage).isInstanceOf(CompletableFuture.class).isNotSameAs(future);
      assertThat((CompletableFuture<?>) stage).isCompletedWithValue(null);
      assertThat((CompletableFuture<?>) future).isCompletedWithValue(null);
    }

    @Test
    @DisplayName("other reference types return null")
    void otherReferenceTypesReturnNull() {
      assertThat(ReturnDefaults.forType(String.class)).isNull();
      assertThat(ReturnDefaults.forType(List.class)).isNull();
      assertThat(ReturnDefaults.forType(Object.class)).isNull();
    }
  }
}
