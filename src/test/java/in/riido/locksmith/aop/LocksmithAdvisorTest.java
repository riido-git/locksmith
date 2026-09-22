package in.riido.locksmith.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.DistributedSemaphore;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

@DisplayName("LocksmithAdvisor")
class LocksmithAdvisorTest {

  private final LocksmithAdvisor advisor = new LocksmithAdvisor(mock(LocksmithInterceptor.class));

  interface Api {
    @DistributedLock(key = "iface")
    void declaredOnInterface();
  }

  @SuppressWarnings("unused")
  static class Service implements Api {
    @DistributedLock(key = "k")
    public void locked() {}

    @DistributedSemaphore(key = "k", permits = "1")
    public void limited() {}

    public void plain() {}

    @Override
    public void declaredOnInterface() {}
  }

  private static Method method(Class<?> type, String name) throws NoSuchMethodException {
    return type.getMethod(name);
  }

  @Test
  @DisplayName("matches a class method with @DistributedLock")
  void matchesLock() throws NoSuchMethodException {
    assertThat(advisor.matches(method(Service.class, "locked"), Service.class)).isTrue();
  }

  @Test
  @DisplayName("matches the interface method when the annotation is declared on the interface")
  void matchesInterfaceDeclaration() throws NoSuchMethodException {
    assertThat(advisor.matches(method(Api.class, "declaredOnInterface"), Service.class)).isTrue();
    assertThat(advisor.matches(method(Service.class, "declaredOnInterface"), Service.class))
        .isTrue();
  }

  @Test
  @DisplayName("matches a method with @DistributedSemaphore")
  void matchesSemaphore() throws NoSuchMethodException {
    assertThat(advisor.matches(method(Service.class, "limited"), Service.class)).isTrue();
  }

  @Test
  @DisplayName("does not match an unannotated method")
  void doesNotMatchPlain() throws NoSuchMethodException {
    assertThat(advisor.matches(method(Service.class, "plain"), Service.class)).isFalse();
  }

  @Test
  @DisplayName("order is HIGHEST_PRECEDENCE + 100")
  void order() {
    assertThat(advisor.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 100);
  }
}
