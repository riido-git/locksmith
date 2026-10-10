package in.riido.locksmith.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.riido.locksmith.DistributedLock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.misc.CompletableFutureWrapper;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where Locksmith's advice runs among other advice: inside method authorization, which Spring
 * Security orders from 100 to 600, and outside {@code @Transactional} at its default order. An
 * advisor at Spring Security's {@code @PreAuthorize} order stands in for the authorization check.
 */
@DisplayName("Locksmith advice order")
class AdviceOrderTest {

  /** The order of Spring Security 7's {@code @PreAuthorize} interceptor. */
  private static final int PRE_AUTHORIZE_ORDER = 200;

  private static final List<String> calls = new CopyOnWriteArrayList<>();
  private static volatile boolean deny;

  private final RedissonClient redisson = mock(RedissonClient.class);

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(AopAutoConfiguration.class, LocksmithAutoConfiguration.class))
          .withUserConfiguration(UserConfiguration.class)
          .withBean(RedissonClient.class, () -> redisson);

  @BeforeEach
  void setUp() {
    calls.clear();
    deny = false;
    RLock lock = mock(RLock.class);
    when(redisson.getLock(anyString())).thenReturn(lock);
    when(lock.tryLockAsync(anyLong(), anyLong(), any(), anyLong()))
        .thenAnswer(
            invocation -> {
              calls.add("lock");
              return new CompletableFutureWrapper<>(true);
            });
    when(lock.unlockAsync(anyLong()))
        .thenAnswer(
            invocation -> {
              calls.add("unlock");
              return new CompletableFutureWrapper<>((Void) null);
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement
  static class UserConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor authorization() {
      MethodInterceptor check =
          invocation -> {
            calls.add("authorize");
            if (deny) {
              throw new IllegalStateException("denied");
            }
            return invocation.proceed();
          };
      DefaultPointcutAdvisor advisor =
          new DefaultPointcutAdvisor(
              new AnnotationMatchingPointcut(null, DistributedLock.class, true), check);
      advisor.setOrder(PRE_AUTHORIZE_ORDER);
      return advisor;
    }

    @Bean
    PlatformTransactionManager transactionManager() {
      PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
      when(manager.getTransaction(any()))
          .thenAnswer(
              invocation -> {
                calls.add("begin");
                return mock(TransactionStatus.class);
              });
      doAnswer(
              invocation -> {
                calls.add("commit");
                return null;
              })
          .when(manager)
          .commit(any());
      return manager;
    }

    @Bean
    OrderService orderService() {
      return new OrderService();
    }
  }

  static class OrderService {
    @Transactional
    @DistributedLock(key = "order:#{#id}")
    public String process(String id) {
      calls.add("method");
      return "processed " + id;
    }
  }

  @Test
  @DisplayName("authorization, then the lock, then the transaction, which commits before unlock")
  void order() {
    runner.run(
        context -> {
          assertThat(context.getBean(OrderService.class).process("42")).isEqualTo("processed 42");

          assertThat(calls)
              .containsExactly("authorize", "lock", "begin", "method", "commit", "unlock");
        });
  }

  @Test
  @DisplayName("a call that authorization denies never reaches Redis")
  void deniedCallNeverReachesRedis() {
    deny = true;
    runner.run(
        context -> {
          assertThatThrownBy(() -> context.getBean(OrderService.class).process("42"))
              .isInstanceOf(IllegalStateException.class)
              .hasMessage("denied");

          assertThat(calls).containsExactly("authorize");
          verify(redisson, never()).getLock(anyString());
        });
  }
}
