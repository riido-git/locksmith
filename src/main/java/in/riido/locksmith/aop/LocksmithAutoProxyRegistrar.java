package in.riido.locksmith.aop;

import org.jspecify.annotations.NonNull;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Registers Spring's infrastructure auto-proxy creator unless one exists, so {@link
 * LocksmithAdvisor} is applied even when Spring Boot's AOP auto-configuration registers none, for
 * example with {@code spring.aop.auto=false} or {@code spring.aop.proxy-target-class=false} and no
 * AspectJ. The same approach as the registrar behind {@code @EnableTransactionManagement}. A
 * creator that already exists is kept, including its proxying mode.
 */
public class LocksmithAutoProxyRegistrar implements ImportBeanDefinitionRegistrar {

  /** Creates the registrar; Spring instantiates it through {@code @Import}. */
  public LocksmithAutoProxyRegistrar() {}

  /**
   * Registers the infrastructure auto-proxy creator if the registry has no auto-proxy creator yet.
   * Without a creator the annotations would silently do nothing.
   *
   * @param importingClassMetadata metadata of the importing configuration; not used
   * @param registry the registry the creator is added to
   */
  @Override
  public void registerBeanDefinitions(
      @NonNull AnnotationMetadata importingClassMetadata,
      @NonNull BeanDefinitionRegistry registry) {
    AopConfigUtils.registerAutoProxyCreatorIfNecessary(registry);
  }
}
