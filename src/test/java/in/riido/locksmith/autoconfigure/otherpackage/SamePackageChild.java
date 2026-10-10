package in.riido.locksmith.autoconfigure.otherpackage;

/**
 * Inherits a package-private annotated method within its package. Top level, so a test can define
 * it again in a class loader of its own.
 */
public class SamePackageChild extends InheritedMethods.PackagePrivateLock {}
