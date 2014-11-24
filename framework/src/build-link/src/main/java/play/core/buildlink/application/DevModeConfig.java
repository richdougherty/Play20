/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.buildlink.application;

import java.io.*;
import java.util.*;

public interface DevModeConfig {

  ApplicationBuildLink applicationBuildLink();

  BuildDocHandler buildDocHandler();

  File projectPath();

  Map<String,String> settings();

}