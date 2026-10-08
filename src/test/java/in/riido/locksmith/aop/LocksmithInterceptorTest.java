package in.riido.locksmith.aop;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.DistributedSemaphore;
import in.riido.locksmith.LocksmithConfigurationException;
import in.riido.locksmith.OnFailure;
import in.riido.locksmith.autoconfigure.LocksmithProperties;
import in.riido.locksmith.lock.LockFailureContext;
import in.riido.locksmith.lock.LockFailureHandler;
import in.riido.locksmith.lock.LockNotAcquiredException;
import in.riido.locksmith.lock.LockOperations;
import in.riido.locksmith.metrics.NoOpLocksmithMetrics;
import in.riido.locksmith.semaphore.SemaphoreFailureContext;
import in.riido.locksmith.semaphore.SemaphoreFailureHandler;
import in.riido.locksmith.semaphore.SemaphoreNotAcquiredException;
import in.riido.locksmith.semaphore.SemaphoreOperations;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.redisson.misc.CompletableFutureWrapper;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;

/**
 * Uses a real {@link LockOperations} and {@link SemaphoreOperations} over a mocked {@link
 * RedissonClient}: their builders are final inner classes, so the mocks sit one level down at the
 * lock and the semaphore.
 */
@DisplayName("LocksmithInterceptor")
class LocksmithInterceptorTest {

  private static final String FULL_KEY = "locksmith:lock:order:42";
  private static final String SEMAPHORE_KEY = "locksmith:semaphore:report:42";
  private static final String PERMIT_ID = "permit-1";

  private RedissonClient redisson;
  private RLock lock;
  private RPermitExpirableSemaphore semaphore;
  private BeanFactory beanFactory;
  private MethodSpecFactory factory;
  private LocksmithInterceptor interceptor;

  static class RecordingHandler implements LockFailureHandler {
    final List<LockFailureContext> contexts = new ArrayList<>();

    @Override
    public Object onFailure(LockFailureContext context) {
      contexts.add(context);
      return "handled";
    }
  }

  static class RecordingSemaphoreHandler implements SemaphoreFailureHandler {
    final List<SemaphoreFailureContext> contexts = new ArrayList<>();

    @Override
    public Object onFailure(SemaphoreFailureContext context) {
      contexts.add(context);
      return "handled";
    }
  }

  @SuppressWarnings("unused")
  static class Service {
    @DistributedSemaphore(key = "report:#{#id}", permits = "3", waitTime = "1s")
    @DistributedLock(key = "order:#{#id}")
    public String both(String id) {
      return "ran";
    }

    @DistributedSemaphore(key = "report:42", permits = "3")
    @DistributedLock(key = "order:#{#id}")
    public String bothFixedPermitKey(String id) {
      return "ran";
    }

    @DistributedSemaphore(key = "report:#{#id}", permits = "3")
    @DistributedLock(key = "order:#{#id}", onFailure = OnFailure.SKIP)
    public int bothLockSkipping(String id) {
      return 1;
    }

    @DistributedSemaphore(key = "report:#{#id}", permits = "3", waitTime = "2s", leaseTime = "20s")
    public String permitThrowing(String id) {
      return "ran";
    }

    @DistributedSemaphore(key = "report:#{#id}", permits = "3", onFailure = OnFailure.SKIP)
    public Optional<String> permitSkipping(String id) {
      return Optional.of("ran");
    }

    @DistributedSemaphore(
        key = "report:#{#id}",
        permits = "3",
        onFailure = OnFailure.HANDLER,
        handler = RecordingSemaphoreHandler.class)
    public Object permitHandled(String id) {
      return "ran";
    }

    @DistributedLock(key = "order:#{#id}")
    public String throwing(String id) {
      return "ran";
    }

    @DistributedLock(key = "order:#{#id}", waitTime = "2s", leaseTime = "10s")
    public String timed(String id) {
      return "ran";
    }

    @DistributedLock(key = "order:#{#id}", onFailure = OnFailure.SKIP)
    public Optional<String> skipping(String id) {
      return Optional.of("ran");
    }

    @DistributedLock(key = "order:#{#id}", onFailure = OnFailure.SKIP)
    public int skippingInt(String id) {
      return 1;
    }

    @DistributedLock(
        key = "order:#{#id}",
        onFailure = OnFailure.HANDLER,
        handler = RecordingHandler.class)
    public Object handled(String id) {
      return "ran";
    }

    @DistributedLock(key = "order:#{#id}")
    public CompletableFuture<String> future(String id) {
      return null;
    }

    @DistributedLock(key = "order:#{#id}")
    public Mono<String> reactive(String id) {
      return null;
    }
  }

  @BeforeEach
  void setUp() {
    redisson = mock(RedissonClient.class);
    lock = mock(RLock.class);
    semaphore = mock(RPermitExpirableSemaphore.class);
    when(redisson.getLock(FULL_KEY)).thenReturn(lock);
    when(redisson.getPermitExpirableSemaphore(SEMAPHORE_KEY)).thenReturn(semaphore);
    when(semaphore.getPermitsAsync()).thenReturn(new CompletableFutureWrapper<>(3));
    when(lock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<>((Void) null));
    when(semaphore.releaseAsync(PERMIT_ID)).thenReturn(new CompletableFutureWrapper<>((Void) null));
    LocksmithProperties properties = new LocksmithProperties(null, null, null);
    LockOperations operations =
        new LockOperations(redisson, properties, new NoOpLocksmithMetrics());
    SemaphoreOperations semaphores =
        new SemaphoreOperations(redisson, properties, new NoOpLocksmithMetrics());
    beanFactory = mock(BeanFactory.class);
    factory =
        spy(
            new MethodSpecFactory(
                new MockEnvironment(), new LocksmithProperties(null, null, null)));
    interceptor =
        new LocksmithInterceptor(
            provider(operations), provider(semaphores), provider(factory), beanFactory);
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T bean) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(bean);
    return provider;
  }

  private static Method method(String name) throws NoSuchMethodException {
    return Service.class.getMethod(name, String.class);
  }

  private MethodInvocation invocation(String name) throws Throwable {
    MethodInvocation invocation = mock(MethodInvocation.class);
    when(invocation.getMethod()).thenReturn(method(name));
    when(invocation.getThis()).thenReturn(new Service());
    when(invocation.getArguments()).thenReturn(new Object[] {"42"});
    when(invocation.proceed()).thenReturn("ran");
    return invocation;
  }

  private void lockAcquired(boolean acquired) {
    when(lock.tryLockAsync(anyLong(), anyLong(), any(), anyLong()))
        .thenReturn(new CompletableFutureWrapper<>(acquired));
  }

  private void permitAcquired(boolean acquired) {
    when(semaphore.tryAcquireAsync(anyInt(), anyLong(), anyLong(), any()))
        .thenReturn(
            new CompletableFutureWrapper<>(acquired ? List.of(PERMIT_ID) : List.<String>of()));
  }

  @Nested
  @DisplayName("lock acquired")
  class Acquired {

    @Test
    @DisplayName("proceeds, returns the method result and releases the lock")
    void proceedsAndReleases() throws Throwable {
      lockAcquired(true);
      MethodInvocation invocation = invocation("throwing");

      assertThat(interceptor.invoke(invocation)).isEqualTo("ran");

      verify(invocation).proceed();
      verify(lock).unlockAsync(Thread.currentThread().getId());
    }

    @Test
    @DisplayName("passes waitTime and leaseTime to the lock in milliseconds")
    void passesDurations() throws Throwable {
      lockAcquired(true);

      interceptor.invoke(invocation("timed"));

      verify(lock).tryLockAsync(eq(2000L), eq(10000L), eq(MILLISECONDS), anyLong());
    }

    @Test
    @DisplayName("passes lease -1 when no leaseTime is set")
    void renewalWhenNoLease() throws Throwable {
      lockAcquired(true);

      interceptor.invoke(invocation("throwing"));

      verify(lock).tryLockAsync(eq(0L), eq(-1L), eq(MILLISECONDS), anyLong());
    }

    @Test
    @DisplayName("propagates the exception from proceed() and still releases the lock")
    void releasesWhenProceedThrows() throws Throwable {
      lockAcquired(true);
      MethodInvocation invocation = invocation("throwing");
      IllegalStateException failure = new IllegalStateException("boom");
      when(invocation.proceed()).thenThrow(failure);

      assertThatThrownBy(() -> interceptor.invoke(invocation)).isSameAs(failure);

      verify(lock).unlockAsync(Thread.currentThread().getId());
    }
  }

  @Nested
  @DisplayName("lock not acquired")
  class NotAcquired {

    @Test
    @DisplayName("THROW: LockNotAcquiredException with the full key and wait time, no proceed")
    void throwPolicy() throws Throwable {
      lockAcquired(false);
      MethodInvocation invocation = invocation("throwing");

      assertThatThrownBy(() -> interceptor.invoke(invocation))
          .isInstanceOfSatisfying(
              LockNotAcquiredException.class,
              e -> {
                assertThat(e.key()).isEqualTo(FULL_KEY);
                assertThat(e.waitTime()).isEqualTo(Duration.ZERO);
              });

      verify(invocation, never()).proceed();
      verify(lock, never()).unlockAsync(anyLong());
    }

    @Test
    @DisplayName("SKIP: returns Optional.empty() for an Optional method, no proceed")
    void skipOptional() throws Throwable {
      lockAcquired(false);
      MethodInvocation invocation = invocation("skipping");

      assertThat(interceptor.invoke(invocation)).isEqualTo(Optional.empty());

      verify(invocation, never()).proceed();
    }

    @Test
    @DisplayName("SKIP: returns 0 for an int method")
    void skipInt() throws Throwable {
      lockAcquired(false);

      assertThat(interceptor.invoke(invocation("skippingInt"))).isEqualTo(0);
    }

    @Test
    @DisplayName("HANDLER: returns the handler's value; the bean is looked up on each failure")
    void handlerPolicy() throws Throwable {
      lockAcquired(false);
      RecordingHandler handler = new RecordingHandler();
      when(beanFactory.getBean(RecordingHandler.class)).thenReturn(handler);
      MethodInvocation invocation = invocation("handled");

      assertThat(interceptor.invoke(invocation)).isEqualTo("handled");
      assertThat(interceptor.invoke(invocation)).isEqualTo("handled");

      verify(beanFactory, times(2)).getBean(RecordingHandler.class);
      verify(invocation, never()).proceed();
      LockFailureContext context = handler.contexts.get(0);
      assertThat(context.key()).isEqualTo(FULL_KEY);
      assertThat(context.method()).isEqualTo(method("handled"));
      assertThat(context.args()).containsExactly("42");
      assertThat(context.waitTime()).isEqualTo(Duration.ZERO);
    }
  }

  @Nested
  @DisplayName("spec cache")
  class SpecCache {

    @Test
    @DisplayName("builds the spec of a method on the first call only")
    void backstop() throws Throwable {
      lockAcquired(true);

      interceptor.invoke(invocation("throwing"));
      interceptor.invoke(invocation("throwing"));

      verify(factory, times(1)).create(method("throwing"));
    }
  }

  @Nested
  @DisplayName("semaphore and lock on one method")
  class Both {

    @Test
    @DisplayName("acquires the permit then the lock; releases the lock then the permit")
    void order() throws Throwable {
      permitAcquired(true);
      lockAcquired(true);
      MethodInvocation invocation = invocation("both");

      assertThat(interceptor.invoke(invocation)).isEqualTo("ran");

      InOrder order = inOrder(semaphore, lock, invocation);
      order
          .verify(semaphore)
          .tryAcquireAsync(1, 1000L, Duration.ofMinutes(5).toMillis(), MILLISECONDS);
      order.verify(lock).tryLockAsync(eq(0L), eq(-1L), eq(MILLISECONDS), anyLong());
      order.verify(invocation).proceed();
      order.verify(lock).unlockAsync(anyLong());
      order.verify(semaphore).releaseAsync(PERMIT_ID);
    }

    @Test
    @DisplayName("releases the lock then the permit when proceed() throws")
    void releasesBothWhenProceedThrows() throws Throwable {
      permitAcquired(true);
      lockAcquired(true);
      MethodInvocation invocation = invocation("both");
      IllegalStateException failure = new IllegalStateException("boom");
      when(invocation.proceed()).thenThrow(failure);

      assertThatThrownBy(() -> interceptor.invoke(invocation)).isSameAs(failure);

      InOrder order = inOrder(lock, semaphore);
      order.verify(lock).unlockAsync(anyLong());
      order.verify(semaphore).releaseAsync(PERMIT_ID);
    }

    @Test
    @DisplayName("lock not acquired: releases the permit, then returns the lock SKIP result")
    void permitReleasedWhenLockFails() throws Throwable {
      permitAcquired(true);
      lockAcquired(false);
      MethodInvocation invocation = invocation("bothLockSkipping");

      assertThat(interceptor.invoke(invocation)).isEqualTo(0);

      verify(semaphore).releaseAsync(PERMIT_ID);
      verify(lock, never()).unlockAsync(anyLong());
      verify(invocation, never()).proceed();
    }

    @Test
    @DisplayName("lock THROW: releases the permit before LockNotAcquiredException propagates")
    void permitReleasedWhenLockThrows() throws Throwable {
      permitAcquired(true);
      lockAcquired(false);
      MethodInvocation invocation = invocation("both");

      assertThatThrownBy(() -> interceptor.invoke(invocation))
          .isInstanceOf(LockNotAcquiredException.class);

      verify(semaphore).releaseAsync(PERMIT_ID);
    }

    @Test
    @DisplayName("lock key with a null part: throws and releases the permit already taken")
    void permitReleasedWhenLockKeyHasNullPart() throws Throwable {
      permitAcquired(true);
      MethodInvocation invocation = invocation("bothFixedPermitKey");
      when(invocation.getArguments()).thenReturn(new Object[] {null});

      assertThatThrownBy(() -> interceptor.invoke(invocation))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessageContaining("part #{#id} resolved to null");

      verify(semaphore).releaseAsync(PERMIT_ID);
      verify(lock, never()).tryLockAsync(anyLong(), anyLong(), any(), anyLong());
      verify(invocation, never()).proceed();
    }

    @Test
    @DisplayName("permit not acquired: the lock is never attempted")
    void lockNotAttemptedWithoutPermit() throws Throwable {
      permitAcquired(false);
      MethodInvocation invocation = invocation("both");

      assertThatThrownBy(() -> interceptor.invoke(invocation))
          .isInstanceOf(SemaphoreNotAcquiredException.class);

      verify(lock, never()).tryLockAsync(anyLong(), anyLong(), any(), anyLong());
      verify(semaphore, never()).releaseAsync(any(String.class));
    }
  }

  @Nested
  @DisplayName("semaphore only")
  class SemaphoreOnly {

    @Test
    @DisplayName("acquires with permits, waitTime and leaseTime in ms, proceeds, releases")
    void proceedsAndReleases() throws Throwable {
      permitAcquired(true);
      MethodInvocation invocation = invocation("permitThrowing");

      assertThat(interceptor.invoke(invocation)).isEqualTo("ran");

      verify(semaphore).tryAcquireAsync(1, 2000L, 20_000L, MILLISECONDS);
      verify(invocation).proceed();
      verify(semaphore).releaseAsync(PERMIT_ID);
      verify(redisson, never()).getLock(any(String.class));
    }

    @Test
    @DisplayName("releases the permit when proceed() throws")
    void releasesWhenProceedThrows() throws Throwable {
      permitAcquired(true);
      MethodInvocation invocation = invocation("permitThrowing");
      IllegalStateException failure = new IllegalStateException("boom");
      when(invocation.proceed()).thenThrow(failure);

      assertThatThrownBy(() -> interceptor.invoke(invocation)).isSameAs(failure);

      verify(semaphore).releaseAsync(PERMIT_ID);
    }

    @Test
    @DisplayName("THROW: SemaphoreNotAcquiredException with full key, permits and wait, no proceed")
    void throwPolicy() throws Throwable {
      permitAcquired(false);
      MethodInvocation invocation = invocation("permitThrowing");

      assertThatThrownBy(() -> interceptor.invoke(invocation))
          .isInstanceOfSatisfying(
              SemaphoreNotAcquiredException.class,
              e -> {
                assertThat(e.key()).isEqualTo(SEMAPHORE_KEY);
                assertThat(e.permits()).isEqualTo(3);
                assertThat(e.waitTime()).isEqualTo(Duration.ofSeconds(2));
              });

      verify(invocation, never()).proceed();
      verify(semaphore, never()).releaseAsync(any(String.class));
    }

    @Test
    @DisplayName("SKIP: returns Optional.empty(), no proceed")
    void skipPolicy() throws Throwable {
      permitAcquired(false);
      MethodInvocation invocation = invocation("permitSkipping");

      assertThat(interceptor.invoke(invocation)).isEqualTo(Optional.empty());

      verify(invocation, never()).proceed();
    }

    @Test
    @DisplayName("HANDLER: returns the handler's value; the bean is looked up on each failure")
    void handlerPolicy() throws Throwable {
      permitAcquired(false);
      RecordingSemaphoreHandler handler = new RecordingSemaphoreHandler();
      when(beanFactory.getBean(RecordingSemaphoreHandler.class)).thenReturn(handler);
      MethodInvocation invocation = invocation("permitHandled");

      assertThat(interceptor.invoke(invocation)).isEqualTo("handled");
      assertThat(interceptor.invoke(invocation)).isEqualTo("handled");

      verify(beanFactory, times(2)).getBean(RecordingSemaphoreHandler.class);
      verify(invocation, never()).proceed();
      SemaphoreFailureContext context = handler.contexts.get(0);
      assertThat(context.key()).isEqualTo(SEMAPHORE_KEY);
      assertThat(context.permits()).isEqualTo(3);
      assertThat(context.method()).isEqualTo(method("permitHandled"));
      assertThat(context.args()).containsExactly("42");
      assertThat(context.waitTime()).isEqualTo(Duration.ZERO);
    }
  }

  @Nested
  @DisplayName("return types whose work can outlive the lock")
  class ReturnTypes {

    @Test
    @DisplayName("the lazy spec fallback rejects a CompletableFuture without @Async like startup")
    void fallbackRejectsFutureWithoutAsync() throws Throwable {
      MethodInvocation invocation = invocation("future");

      assertThatThrownBy(() -> interceptor.invoke(invocation))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessageContaining("mark the method @Async");

      verify(invocation, never()).proceed();
    }

    @Test
    @DisplayName("the lazy spec fallback rejects a reactive return type like startup does")
    void fallbackRejectsReactive() throws Throwable {
      MethodInvocation invocation = invocation("reactive");

      assertThatThrownBy(() -> interceptor.invoke(invocation))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessageContaining("reactive return types are not supported");

      verify(invocation, never()).proceed();
    }
  }
}
