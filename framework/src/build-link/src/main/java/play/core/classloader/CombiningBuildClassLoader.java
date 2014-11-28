package play.core.classloader;

import java.util.*;

/**
 * Wraps a ClassLoader filtering out every class except some that are needed
 * to bridge a Play build and a Play server.
 */
public class CombiningBuildClassLoader extends ClassLoader {

  private static final List<String> sharedClasses;
  static {
    sharedClasses = Collections.<String>unmodifiableList(Arrays.<String>asList(new String[] {
      play.core.buildlink.application.ApplicationBuildLink.class.getName(),
      play.core.buildlink.application.BuildDocHandler.class.getName(),
      play.core.buildlink.application.DevModeConfig.class.getName(),
      play.core.buildlink.application.DevModeServer.class.getName(),
      play.core.buildlink.BuildLink.class.getName(),
      play.core.buildlink.DevModeConfig2.class.getName(),
      play.core.buildlink.DevModeConfig2.class.getName() + "$FileMapping",
      play.core.buildlink.BuildResult.class.getName(),
      play.core.buildlink.SameBuild.class.getName(),
      play.core.buildlink.FreshBuild.class.getName(),
      play.core.buildlink.FailedBuild.class.getName(),
      // play.core.buildlink.application.PlayDevServer.class.getName(),
      // play.core.buildlink.application.PlayDevServer.class.getName() + "$PlayApplicationLink",
      // play.core.buildlink.application.PlayDevServer.class.getName() + "$DelegatedResourcesClassLoader",
      play.api.UsefulException.class.getName(),
      play.api.PlayException.class.getName(),
      play.api.PlayException.InterestingLines.class.getName(),
      play.api.PlayException.RichDescription.class.getName(),
      play.api.PlayException.ExceptionSource.class.getName(),
      play.api.PlayException.ExceptionAttachment.class.getName()
    }));
  }

  public static boolean isSharedClass(String name) {
    return sharedClasses.contains(name);
  }

  private final ClassLoader buildLoader;

  public CombiningBuildClassLoader(ClassLoader parentLoader, ClassLoader buildLoader) {
    super(parentLoader);
    this.buildLoader = buildLoader;
  }

  @Override
  public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (isSharedClass(name)) {
      return buildLoader.loadClass(name);
    } else {
      return super.loadClass(name, resolve);
    }
  }

  @Override
  public String toString() {
    return "FilteredBuildClassLoader, using parent: " + getParent();
  }

}
