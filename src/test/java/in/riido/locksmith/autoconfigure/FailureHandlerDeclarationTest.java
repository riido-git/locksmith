package in.riido.locksmith.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.OnFailure;
import in.riido.locksmith.lock.LockFailureContext;
import in.riido.locksmith.lock.LockFailureHandler;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.misc.CompletableFutureWrapper;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;

/**
 * Failure handler beans declared in ways Spring cannot tell the class of, or exposes twice: by
 * their interface from a {@code @Bean} method, which Spring registers after plain beans, and behind
 * a scoped proxy, which adds a hidden target bean. Each is counted once at startup and called.
 */
@DisplayName("failure handler beans declared by interface or behind a scoped proxy")
class FailureHandlerDeclarationTest {

  private final RedissonClient redisson = mock(RedissonClient.class);

  @BeforeEach
  void setUp() {
    Audit.created.set(0);
    Other.created.set(0);
    RLock lock = mock(RLock.class);
    when(redisson.getLock(anyString())).thenReturn(lock);
    when(lock.tryLockAsync(anyLong(), anyLong(), any(), anyLong()))
        .thenReturn(new CompletableFutureWrapper<>(false));
  }

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(AopAutoConfiguration.class, LocksmithAutoConfiguration.class))
        .withBean(RedissonClient.class, () -> redisson)
        .withBean(OrderService.class);
  }

  static class Audit implements LockFailureHandler {
    static final AtomicInteger created = new AtomicInteger();

    Audit() {
      created.incrementAndGet();
    }

    @Override
    public Object onFailure(LockFailureContext context) {
      return "handled";
    }
  }

  /** A handler of another type, which a failure of the order lock must not create. */
  static class Other implements LockFailureHandler {
    static final AtomicInteger created = new AtomicInteger();

    Other() {
      created.incrementAndGet();
    }

    @Override
    public Object onFailure(LockFailureContext context) {
      return "other";
    }
  }

  static class OrderService {
    @DistributedLock(key = "order", onFailure = OnFailure.HANDLER, handler = Audit.class)
    public String run() {
      return "ran";
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class InterfaceTyped {
    @Bean
    LockFailureHandler audit() {
      return new Audit();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class LazyInterfaceTyped {
    @Bean
    @Lazy
    LockFailureHandler audit() {
      return new Audit();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class PrototypeInterfaceTyped {
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    LockFailureHandler audit() {
      return new Audit();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class LazyConcreteTyped {
    @Bean
    @Lazy
    Audit audit() {
      return new Audit();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class PrototypeConcreteTyped {
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    Audit audit() {
      return new Audit();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ScopedProxy {
    @Bean
    @Scope(
        value = ConfigurableBeanFactory.SCOPE_PROTOTYPE,
        proxyMode = ScopedProxyMode.TARGET_CLASS)
    Audit audit() {
      return new Audit();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class WithPrototypeOther {
    @Bean
    Audit audit() {
      return new Audit();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    Other other() {
      return new Other();
    }
  }

  @Test
  @DisplayName(
      "counts a handler declared by its interface from a @Bean method, created after the"
          + " annotated bean, and calls it")
  void interfaceTypedBean() {
    runner()
        .withUserConfiguration(InterfaceTyped.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(OrderService.class).run()).isEqualTo("handled");
            });
  }

  @ParameterizedTest
  @ValueSource(classes = {LazyInterfaceTyped.class, PrototypeInterfaceTyped.class})
  @DisplayName("explains an unresolved lazy or prototype handler without creating it")
  void interfaceTypedDeferredHandlerRequiresConcreteType(Class<?> configuration) {
    runner()
        .withUserConfiguration(configuration)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasMessageContaining("declare the concrete handler type")
                  .hasMessageContaining("without creating it");
              assertThat(Audit.created).hasValue(0);
            });
  }

  @ParameterizedTest
  @ValueSource(classes = {LazyConcreteTyped.class, PrototypeConcreteTyped.class})
  @DisplayName("identifies a concrete lazy or prototype handler without creating it at startup")
  void concreteTypedDeferredHandlerIsCreatedOnFailure(Class<?> configuration) {
    runner()
        .withUserConfiguration(configuration)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(Audit.created).hasValue(0);
              assertThat(context.getBean(OrderService.class).run()).isEqualTo("handled");
              assertThat(Audit.created).hasValue(1);
            });
  }

  @Test
  @DisplayName(
      "counts a handler behind a scoped proxy once, not with its hidden target, and calls it")
  void scopedProxy() {
    runner()
        .withUserConfiguration(ScopedProxy.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(OrderService.class).run()).isEqualTo("handled");
            });
  }

  @Test
  @DisplayName("creates no handler bean of another type on a failure")
  void createsNoOtherHandler() {
    runner()
        .withUserConfiguration(WithPrototypeOther.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(OrderService.class).run()).isEqualTo("handled");
              assertThat(context.getBean(OrderService.class).run()).isEqualTo("handled");
              assertThat(Other.created).hasValue(0);
            });
  }
}
