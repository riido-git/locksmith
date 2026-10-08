package in.riido.locksmith;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/** Disables a test class when no Docker daemon is reachable, so Testcontainers tests skip. */
public final class DockerAvailableCondition implements ExecutionCondition {

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    return DockerClientFactory.instance().isDockerAvailable()
        ? ConditionEvaluationResult.enabled("Docker is available")
        : ConditionEvaluationResult.disabled("Docker is not available");
  }
}
