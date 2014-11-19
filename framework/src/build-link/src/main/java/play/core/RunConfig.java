/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core;

import java.util.*;

// Add as an argument to NettyServer.devMod 
public class RunConfig implements BuildResult {
  public String projectPath;
  // Settings to add into System.properties for server (not sure if needed)
  public Map<String,String> properties;
  // Settings from SBT to add into Config
  public Map<String,String> devSettings;
}