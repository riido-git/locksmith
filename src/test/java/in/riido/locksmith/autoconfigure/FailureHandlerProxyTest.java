package in.riido.locksmith.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.DistributedSemaphore;
import in.riido.locksmith.OnFailure;
import in.riido.locksmith.lock.LockFailureContext;
import in.riido.locksmith.lock.LockFailureHandler;
import in.riido.locksmith.semaphore.SemaphoreFailureContext;
import in.riido.locksmith.semaphore.SemaphoreFailureHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.redisson.misc.CompletableFutureWrapper;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

/**
 * A failure handler that Spring advises, here with {@code @Transactional}, is a JDK proxy under
 * interface-based proxying ({@code spring.aop.proxy-target-class=false}), and a JDK proxy is not an
 * instance of the handler's class. Locksmith still counts it at startup and calls it, its advice
 * included, whichever bean Spring creates first.
 */
@DisplayName("an advised failure handler under interface-based proxying")
class FailureHandlerProxyTest {

  private static final List<String> calls = new CopyOnWriteArrayList<>();

  private final RedissonClient redisson = mock(RedissonClient.class);

  @BeforeEach
  void setUp() {
    calls.clear();
    RLock lock = mock(RLock.class);
    when(redisson.getLock(anyString())).thenReturn(lock);
    when(lock.tryLockAsync(anyLong(), anyLong(), any(), anyLong()))
        .thenReturn(new CompletableFutureWrapper<>(false));
    RPermitExpirableSemaphore semaphore = mock(RPermitExpirableSemaphore.class);
    when(redisson.getPermitExpirableSemaphore(anyString())).thenReturn(semaphore);
    when(semaphore.getPermitsAsync()).thenReturn(new CompletableFutureWrapper<>(1));
    when(semaphore.tryAcquireAsync(anyInt(), anyLong(), anyLong(), any()))
        .thenReturn(new CompletableFutureWrapper<>(List.<String>of()));
  }

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(AopAutoConfiguration.class, LocksmithAutoConfiguration.class))
        .withPropertyValues("spring.aop.proxy-target-class=false")
        .withUserConfiguration(TransactionConfiguration.class)
        .withBean(RedissonClient.class, () -> redisson);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement
  static class TransactionConfiguration {

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
  }

  static class AuditLockHandler implements LockFailureHandler {
    @Override
    @Transactional
    public Object onFailure(LockFailureContext context) {
      calls.add("lock handler");
      return "handled";
    }
  }

  static class AuditSemaphoreHandler implements SemaphoreFailureHandler {
    @Override
    @Transactional
    public Object onFailure(SemaphoreFailureContext context) {
      calls.add("semaphore handler");
      return "handled";
    }
  }

  static class OrderService {
    @DistributedLock(key = "order", onFailure = OnFailure.HANDLER, handler = AuditLockHandler.class)
    public String locked() {
      return "ran";
    }

    @DistributedSemaphore(
        key = "report",
        permits = "1",
        onFailure = OnFailure.HANDLER,
        handler = AuditSemaphoreHandler.class)
    public String limited() {
      return "ran";
    }
  }

  private void assertHandledInsideTransactions(ApplicationContextRunner runner) {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBeansOfType(LockFailureHandler.class).values())
              .singleElement()
              .matches(handler -> Proxy.isProxyClass(handler.getClass()));
          OrderService service = context.getBean(OrderService.class);

          assertThat(service.locked()).isEqualTo("handled");
          assertThat(service.limited()).isEqualTo("handled");

          assertThat(calls)
              .containsExactly(
                  "begin", "lock handler", "commit", "begin", "semaphore handler", "commit");
        });
  }

  @Test
  @DisplayName("starts and calls the handlers in their transactions when created after the bean")
  void handlersCreatedAfter() {
    assertHandledInsideTransactions(
        runner()
            .withBean(OrderService.class)
            .withBean(AuditLockHandler.class)
            .withBean(AuditSemaphoreHandler.class));
  }

  @Test
  @DisplayName("starts and calls the handlers in their transactions when created before the bean")
  void handlersCreatedBefore() {
    assertHandledInsideTransactions(
        runner()
            .withBean(AuditLockHandler.class)
            .withBean(AuditSemaphoreHandler.class)
            .withBean(OrderService.class));
  }
}
