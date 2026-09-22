package in.riido.locksmith.aop;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.concurrent.CompletionStage;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    @DistributedSemaphore(key = "report:#{#id}", permits = "3")
    @DistributedLock(key = "order:#{#id}")
    public CompletableFuture<String> bothAsync(String id) {
      return null;
    }

    @DistributedLock(key = "order:#{#id}")
    public CompletionStage<String> lockAsync(String id) {
      return null;
    }

    @DistributedSemaphore(key = "report:#{#id}", permits = "3")
    @DistributedLock(key = "order:#{#id}", onFailure = OnFailure.SKIP)
    public CompletableFuture<String> bothAsyncLockSkipping(String id) {
      return null;
    }

    @DistributedSemaphore(key = "report:#{#id}", permits = "3", onFailure = OnFailure.SKIP)
    public CompletionStage<String> permitAsyncSkipping(String id) {
      return null;
    }

    @DistributedLock(
        key = "order:#{#id}",
        onFailure = OnFailure.HANDLER,
        handler = RecordingHandler.class)
    public CompletableFuture<Object> lockAsyncHandled(String id) {
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
    when(semaphore.getPermits()).thenReturn(3);
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

  private void lockAcquired(boolean acquired) throws InterruptedException {
    when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(acquired);
  }

  private void permitAcquired(boolean acquired) throws InterruptedException {
    when(semaphore.tryAcquire(anyLong(), anyLong(), any())).thenReturn(acquired ? PERMIT_ID : null);
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
      verify(lock).unlock();
    }

    @Test
    @DisplayName("passes waitTime and leaseTime to the lock in milliseconds")
    void passesDurations() throws Throwable {
      lockAcquired(true);

      interceptor.invoke(invocation("timed"));

      verify(lock).tryLock(2000L, 10000L, MILLISECONDS);
    }

    @Test
    @DisplayName("passes lease -1 when no leaseTime is set")
    void renewalWhenNoLease() throws Throwable {
      lockAcquired(true);

      interceptor.invoke(invocation("throwing"));

      verify(lock).tryLock(0L, -1L, MILLISECONDS);
    }

    @Test
    @DisplayName("propagates the exception from proceed() and still releases the lock")
    void releasesWhenProceedThrows() throws Throwable {
      lockAcquired(true);
      MethodInvocation invocation = invocation("throwing");
      IllegalStateException failure = new IllegalStateException("boom");
      when(invocation.proceed()).thenThrow(failure);

      assertThatThrownBy(() -> interceptor.invoke(invocation)).isSameAs(failure);

      verify(lock).unlock();
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
      verify(lock, never()).unlock();
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
    @DisplayName("HANDLER: returns the handler's value; the bean is looked up once across calls")
    void handlerPolicy() throws Throwable {
      lockAcquired(false);
      RecordingHandler handler = new RecordingHandler();
      when(beanFactory.getBean(RecordingHandler.class)).thenReturn(handler);
      MethodInvocation invocation = invocation("handled");

      assertThat(interceptor.invoke(invocation)).isEqualTo("handled");
      assertThat(interceptor.invoke(invocation)).isEqualTo("handled");

      verify(beanFactory, times(1)).getBean(RecordingHandler.class);
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
    @DisplayName("builds the spec of an unregistered method on the first call only")
    void backstop() throws Throwable {
      lockAcquired(true);

      interceptor.invoke(invocation("throwing"));
      interceptor.invoke(invocation("throwing"));

      verify(factory, times(1)).create(method("throwing"));
    }

    @Test
    @DisplayName("uses a registered spec without calling the factory")
    void registered() throws Throwable {
      lockAcquired(true);
      Method method = method("throwing");
      interceptor.register(
          method,
          new MethodSpecFactory(new MockEnvironment(), new LocksmithProperties(null, null, null))
              .create(method));

      interceptor.invoke(invocation("throwing"));

      verify(factory, never()).create(any());
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
      order.verify(semaphore).tryAcquire(1000L, Duration.ofMinutes(5).toMillis(), MILLISECONDS);
      order.verify(lock).tryLock(0L, -1L, MILLISECONDS);
      order.verify(invocation).proceed();
      order.verify(lock).unlock();
      order.verify(semaphore).release(PERMIT_ID);
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
      order.verify(lock).unlock();
      order.verify(semaphore).release(PERMIT_ID);
    }

    @Test
    @DisplayName("lock not acquired: releases the permit, then returns the lock SKIP result")
    void permitReleasedWhenLockFails() throws Throwable {
      permitAcquired(true);
      lockAcquired(false);
      MethodInvocation invocation = invocation("bothLockSkipping");

      assertThat(interceptor.invoke(invocation)).isEqualTo(0);

      verify(semaphore).release(PERMIT_ID);
      verify(lock, never()).unlock();
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

      verify(semaphore).release(PERMIT_ID);
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

      verify(semaphore).release(PERMIT_ID);
      verify(lock, never()).tryLock(anyLong(), anyLong(), any());
      verify(invocation, never()).proceed();
    }

    @Test
    @DisplayName("permit not acquired: the lock is never attempted")
    void lockNotAttemptedWithoutPermit() throws Throwable {
      permitAcquired(false);
      MethodInvocation invocation = invocation("both");

      assertThatThrownBy(() -> interceptor.invoke(invocation))
          .isInstanceOf(SemaphoreNotAcquiredException.class);

      verify(lock, never()).tryLock(anyLong(), anyLong(), any());
      verify(semaphore, never()).release(any(String.class));
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

      verify(semaphore).tryAcquire(2000L, 20_000L, MILLISECONDS);
      verify(invocation).proceed();
      verify(semaphore).release(PERMIT_ID);
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

      verify(semaphore).release(PERMIT_ID);
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
      verify(semaphore, never()).release(any(String.class));
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
    @DisplayName("HANDLER: returns the handler's value; the bean is looked up once across calls")
    void handlerPolicy() throws Throwable {
      permitAcquired(false);
      RecordingSemaphoreHandler handler = new RecordingSemaphoreHandler();
      when(beanFactory.getBean(RecordingSemaphoreHandler.class)).thenReturn(handler);
      MethodInvocation invocation = invocation("permitHandled");

      assertThat(interceptor.invoke(invocation)).isEqualTo("handled");
      assertThat(interceptor.invoke(invocation)).isEqualTo("handled");

      verify(beanFactory, times(1)).getBean(RecordingSemaphoreHandler.class);
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
  @DisplayName("method returning a CompletionStage")
  class Async {

    private CompletableFuture<Object> work;

    @BeforeEach
    void redisAsync() {
      work = new CompletableFuture<>();
      when(lock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<>((Void) null));
      when(semaphore.releaseAsync(PERMIT_ID))
          .thenReturn(new CompletableFutureWrapper<>((Void) null));
    }

    private void asyncLockAcquired(boolean acquired) {
      when(lock.tryLockAsync(anyLong(), anyLong(), any(), anyLong()))
          .thenReturn(new CompletableFutureWrapper<>(acquired));
    }

    private MethodInvocation asyncInvocation(String name) throws Throwable {
      MethodInvocation invocation = invocation(name);
      when(invocation.proceed()).thenReturn(work);
      return invocation;
    }

    private long ownerId() {
      ArgumentCaptor<Long> owner = ArgumentCaptor.forClass(Long.class);
      verify(lock).tryLockAsync(eq(0L), eq(-1L), eq(MILLISECONDS), owner.capture());
      return owner.getValue();
    }

    @Test
    @DisplayName("returns the method's own stage and releases nothing until it completes")
    void holdsUntilCompletion() throws Throwable {
      permitAcquired(true);
      asyncLockAcquired(true);

      Object result = interceptor.invoke(asyncInvocation("bothAsync"));

      assertThat(result).isSameAs(work);
      verify(lock, never()).unlockAsync(anyLong());
      verify(semaphore, never()).releaseAsync(any(String.class));

      work.complete("done");

      long owner = ownerId();
      InOrder order = inOrder(lock, semaphore);
      order.verify(lock).unlockAsync(owner);
      order.verify(semaphore).releaseAsync(PERMIT_ID);
      verify(lock, never()).tryLock(anyLong(), anyLong(), any());
      verify(lock, never()).unlock();
      verify(semaphore, never()).release(any(String.class));
    }

    @Test
    @DisplayName("the lock is owned by a generated negative id, not by the calling thread")
    void ownerIdIsGenerated() throws Throwable {
      asyncLockAcquired(true);

      interceptor.invoke(asyncInvocation("lockAsync"));

      assertThat(ownerId()).isNegative();
    }

    @Test
    @DisplayName("releases the lock then the permit when the stage completes exceptionally")
    void releasesOnExceptionalCompletion() throws Throwable {
      permitAcquired(true);
      asyncLockAcquired(true);
      interceptor.invoke(asyncInvocation("bothAsync"));

      work.completeExceptionally(new IllegalStateException("boom"));

      long owner = ownerId();
      InOrder order = inOrder(lock, semaphore);
      order.verify(lock).unlockAsync(owner);
      order.verify(semaphore).releaseAsync(PERMIT_ID);
    }

    @Test
    @DisplayName("releases the lock then the permit when the caller cancels the returned stage")
    void releasesOnCancel() throws Throwable {
      permitAcquired(true);
      asyncLockAcquired(true);
      CompletableFuture<?> returned =
          (CompletableFuture<?>) interceptor.invoke(asyncInvocation("bothAsync"));

      returned.cancel(true);

      assertThat(work).isCancelled();
      long owner = ownerId();
      InOrder order = inOrder(lock, semaphore);
      order.verify(lock).unlockAsync(owner);
      order.verify(semaphore).releaseAsync(PERMIT_ID);
    }

    @Test
    @DisplayName("method throws: releases the lock then the permit before the exception propagates")
    void releasesWhenProceedThrows() throws Throwable {
      permitAcquired(true);
      asyncLockAcquired(true);
      MethodInvocation invocation = asyncInvocation("bothAsync");
      IllegalStateException failure = new IllegalStateException("boom");
      when(invocation.proceed()).thenThrow(failure);

      assertThatThrownBy(() -> interceptor.invoke(invocation)).isSameAs(failure);

      long owner = ownerId();
      InOrder order = inOrder(lock, semaphore);
      order.verify(lock).unlockAsync(owner);
      order.verify(semaphore).release(PERMIT_ID);
    }

    @Test
    @DisplayName("method returns null: releases the lock then the permit and returns null")
    void releasesWhenNull() throws Throwable {
      permitAcquired(true);
      asyncLockAcquired(true);
      MethodInvocation invocation = asyncInvocation("bothAsync");
      when(invocation.proceed()).thenReturn(null);

      assertThat(interceptor.invoke(invocation)).isNull();

      long owner = ownerId();
      InOrder order = inOrder(lock, semaphore);
      order.verify(lock).unlockAsync(owner);
      order.verify(semaphore).release(PERMIT_ID);
    }

    @Test
    @DisplayName("lock THROW: LockNotAcquiredException thrown synchronously, permit released")
    void throwIsSynchronous() throws Throwable {
      permitAcquired(true);
      asyncLockAcquired(false);
      MethodInvocation invocation = asyncInvocation("bothAsync");

      assertThatThrownBy(() -> interceptor.invoke(invocation))
          .isInstanceOf(LockNotAcquiredException.class);

      verify(invocation, never()).proceed();
      verify(semaphore).release(PERMIT_ID);
    }

    @Test
    @DisplayName("key with a null part: thrown synchronously, before anything is acquired")
    void nullKeyPartIsSynchronous() throws Throwable {
      MethodInvocation invocation = asyncInvocation("bothAsync");
      when(invocation.getArguments()).thenReturn(new Object[] {null});

      assertThatThrownBy(() -> interceptor.invoke(invocation))
          .isInstanceOf(LocksmithConfigurationException.class)
          .hasMessageContaining("part #{#id} resolved to null");

      verify(semaphore, never()).tryAcquire(anyLong(), anyLong(), any());
      verify(lock, never()).tryLockAsync(anyLong(), anyLong(), any(), anyLong());
      verify(invocation, never()).proceed();
    }

    @Test
    @DisplayName("lock SKIP: returns a completed future holding null, permit released")
    void lockSkip() throws Throwable {
      permitAcquired(true);
      asyncLockAcquired(false);
      MethodInvocation invocation = asyncInvocation("bothAsyncLockSkipping");

      Object result = interceptor.invoke(invocation);

      assertThat(result).isInstanceOf(CompletableFuture.class);
      assertThat((CompletableFuture<?>) result).isCompletedWithValue(null);
      verify(invocation, never()).proceed();
      verify(semaphore).release(PERMIT_ID);
    }

    @Test
    @DisplayName("semaphore SKIP: returns a completed future holding null, lock never attempted")
    void permitSkip() throws Throwable {
      permitAcquired(false);
      MethodInvocation invocation = asyncInvocation("permitAsyncSkipping");

      Object result = interceptor.invoke(invocation);

      assertThat((CompletableFuture<?>) result).isCompletedWithValue(null);
      verify(invocation, never()).proceed();
      verify(lock, never()).tryLockAsync(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("HANDLER: returns whatever the handler returns")
    void handler() throws Throwable {
      asyncLockAcquired(false);
      when(beanFactory.getBean(RecordingHandler.class)).thenReturn(new RecordingHandler());

      assertThat(interceptor.invoke(asyncInvocation("lockAsyncHandled"))).isEqualTo("handled");
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
