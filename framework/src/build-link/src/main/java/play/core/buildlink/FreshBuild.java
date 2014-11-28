/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.buildlink;

import java.io.File;

public interface FreshBuild extends BuildResult {
  File[] classpath();
}