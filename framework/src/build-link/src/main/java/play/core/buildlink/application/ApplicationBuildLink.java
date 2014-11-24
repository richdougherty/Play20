/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.core.buildlink.application;

import java.io.*;
import java.util.*;

public interface ApplicationBuildLink {

    public Object reload();

    public void forceReload();

    public Object[] findSource(String className, Integer line);

}