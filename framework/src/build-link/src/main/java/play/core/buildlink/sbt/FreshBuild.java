/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.buildlink.sbt;

import java.util.*;

public class FreshBuild implements BuildResult {
  public List<String> reloadableClasspath;
  public List<String> assetsClasspath;
}