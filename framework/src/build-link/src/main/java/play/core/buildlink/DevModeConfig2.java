/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.buildlink;

import java.io.*;
import java.util.*;

public final class DevModeConfig2 {

  public static final class FileMapping {
    private final File file;
    private final String relative;
    public FileMapping(File file, String relative) {
      this.file = file;
      this.relative = relative;
    }
    public File file() {
      return file;
    }
    public String relative() {
      return relative;
    }
  }

  private final File[] dependencyClasspath;

  private final File[] docsClasspath;

  private final FileMapping[] allAssets;

  private final Integer httpPort;

  private final Integer httpsPort;

  private final Map<String,String> systemProperties;

  private final Map<String,String> settings;

  private final File projectPath;

  public DevModeConfig2(
    File[] dependencyClasspath,
    File[] docsClasspath,
    FileMapping[] allAssets,
    Integer httpPort,
    Integer httpsPort,
    Map<String,String> systemProperties,
    Map<String,String> settings,
    File projectPath
  ) {
    this.dependencyClasspath = dependencyClasspath;
    this.docsClasspath = docsClasspath;
    this.allAssets = allAssets;
    this.httpPort = httpPort;
    this.httpsPort = httpsPort;
    this.systemProperties = systemProperties;
    this.settings = settings;
    this.projectPath = projectPath;
  }

  public File[] dependencyClasspath() {
    return dependencyClasspath;
  }

  public File[] docsClasspath() {
    return docsClasspath;
  }

  public FileMapping[] allAssets() {
    return allAssets;
  }

  public Integer httpPort() {
    return httpPort;
  }

  public Integer httpsPort() {
    return httpsPort;
  }

  public Map<String,String> systemProperties() {
    return systemProperties;
  }

  public Map<String,String> settings() {
    return settings;
  }

  public File projectPath() {
    return projectPath;
  }

}