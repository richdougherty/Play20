/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.buildlink.sbt;

import java.io.*;
import java.util.*;

public interface SbtBuildLink {

    BuildResult build();

    public Object[] findSource(String className, Integer line);

}