package in.riido.locksmith.autoconfigure.otherpackage;

import in.riido.locksmith.DistributedLock;
import in.riido.locksmith.DistributedSemaphore;

/** Annotated methods for beans that inherit them from another package. */
public final class InheritedMethods {

  private InheritedMethods() {}

  public static class PackagePrivateLock {
    @DistributedLock(key = "inherited")
    void run() {}
  }

  public static class PackagePrivateSemaphore {
    @DistributedSemaphore(key = "inherited", permits = "1")
    void run() {}
  }

  public static class ProtectedLock {
    @DistributedLock(key = "inherited-protected")
    protected void run() {}
  }
}
