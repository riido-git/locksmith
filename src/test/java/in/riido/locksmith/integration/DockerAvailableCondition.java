package in.riido.locksmith.integration;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * JUnit 5 condition that checks if Docker is available for Testcontainers.
 *
 * <p>Usage: Apply @ExtendWith(DockerAvailableCondition.class) to test classes that require Docker.
 */
public class DockerAvailableCondition implements ExecutionCondition {

  private static final ConditionEvaluationResult ENABLED =
      ConditionEvaluationResult.enabled("Docker is available");
  private static final ConditionEvaluationResult DISABLED =
      ConditionEvaluationResult.disabled("Docker is not available - skipping integration tests");

  @Override
  public @NonNull ConditionEvaluationResult evaluateExecutionCondition(
      @NonNull ExtensionContext context) {
    return isDockerAvailable() ? ENABLED : DISABLED;
  }

  private boolean isDockerAvailable() {
    try {
      DockerClientFactory.instance().client();
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
