/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core;

import java.util.*;

public class NewBuild implements BuildResult {
  public List<String> reloadableClasspath;
  public List<String> assetsClasspath;
}