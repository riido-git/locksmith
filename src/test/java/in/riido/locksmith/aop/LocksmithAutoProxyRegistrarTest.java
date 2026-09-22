package in.riido.locksmith.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.LocksmithConfigurationException;
import in.riido.locksmith.autoconfigure.LocksmithAutoConfiguration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("LocksmithAutoProxyRegistrar")
class LocksmithAutoProxyRegistrarTest {

  private final RedissonClient redisson = mock(RedissonClient.class);
  private final RLock lock = mock(RLock.class);

  @BeforeEach
  void lockAlwaysFree() throws InterruptedException {
    when(redisson.getLock(anyString())).thenReturn(lock);
    when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
  }

  static class ClassService {
    @DistributedLock(key = "k")
    public void run() {}
  }

  interface Api {
    @DistributedLock(key = "k")
    void run();
  }

  static class ApiService implements Api {
    @Override
    public void run() {}
  }

  interface BlankKeyApi {
    @DistributedLock(key = " ")
    void run();
  }

  static class BlankKeyService implements BlankKeyApi {
    @Override
    public void run() {}
  }

  interface PlainApi {
    void run();
  }

  static class BlankKeyOnImplementation implements PlainApi {
    @Override
    @DistributedLock(key = " ")
    public void run() {}
  }

  private static Throwable configurationFailure(Throwable failure) {
    while (failure != null && !(failure instanceof LocksmithConfigurationException)) {
      failure = failure.getCause();
    }
    return failure;
  }

  private ApplicationContextRunner runner(Class<?>... autoConfigurations) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(autoConfigurations))
        .withBean(RedissonClient.class, () -> redisson);
  }

  @Test
  @DisplayName("without Boot's AOP auto-configuration the bean is proxied and the lock is taken")
  void withoutAopAutoConfiguration() {
    runner(LocksmithAutoConfiguration.class)
        .withBean(ClassService.class)
        .run(
            context -> {
              ClassService service = context.getBean(ClassService.class);
              assertThat(AopUtils.isAopProxy(service)).isTrue();

              service.run();

              verify(lock, times(1)).tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
              verify(lock, times(1)).unlock();
            });
  }

  @Test
  @DisplayName("with proxy-target-class=false an interface bean gets a JDK proxy and the lock")
  void interfaceProxies() {
    runner(AopAutoConfiguration.class, LocksmithAutoConfiguration.class)
        .withPropertyValues("spring.aop.proxy-target-class=false")
        .withBean(ApiService.class)
        .run(
            context -> {
              Api service = context.getBean(Api.class);
              assertThat(AopUtils.isJdkDynamicProxy(service)).isTrue();

              service.run();

              verify(lock, times(1)).tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
              verify(lock, times(1)).unlock();
            });
  }

  @Test
  @DisplayName("with proxy-target-class=false a blank key on an interface still fails the startup")
  void interfaceProxiesStillValidated() {
    runner(AopAutoConfiguration.class, LocksmithAutoConfiguration.class)
        .withPropertyValues("spring.aop.proxy-target-class=false")
        .withBean(BlankKeyService.class)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(configurationFailure(context.getStartupFailure()))
                  .hasMessageContaining(BlankKeyService.class.getName() + ".run")
                  .hasMessageContaining("key must not be blank")
                  .hasMessageNotContaining("$Proxy");
            });
  }

  @Test
  @DisplayName("with proxy-target-class=false a blank key on the implementing method fails startup")
  void implementingMethodValidated() {
    runner(AopAutoConfiguration.class, LocksmithAutoConfiguration.class)
        .withPropertyValues("spring.aop.proxy-target-class=false")
        .withBean(BlankKeyOnImplementation.class)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(configurationFailure(context.getStartupFailure()))
                  .hasMessageContaining(BlankKeyOnImplementation.class.getName() + ".run")
                  .hasMessageContaining("key must not be blank");
            });
  }
}
