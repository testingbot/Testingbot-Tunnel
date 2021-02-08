//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package com.testingbot.tunnel;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.eclipse.jetty.util.log.AbstractLogger;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>
 * Implementation of Jetty {@link Logger} based on {@link java.util.logging.Logger}.
 * </p>
 *
 * <p>
 * You can also set the logger level using <a href="http://docs.oracle.com/javase/8/docs/api/java/util/logging/package-summary.html">
 * standard java.util.logging configuration</a>.
 * </p>
 *
 * Configuration Properties:
 * <dl>
 * <dt>${name|hierarchy}.LEVEL=(ALL|DEBUG|INFO|WARN|OFF)</dt>
 * <dd>
 * Sets the level that the Logger should log at.<br>
 * Names can be a package name, or a fully qualified class name.<br>
 * Default: The default from the java.util.logging mechanism/configuration
 * <br>
 * <dt>org.eclipse.jetty.util.log.javautil.PROPERTIES=&lt;property-resource-name&gt;</dt>
 * <dd>If set, it is used as a classpath resource name to find a java.util.logging
 * property file.
 * <br>
 * Default: null
 * </dd>
 * <dt>org.eclipse.jetty.util.log.javautil.SOURCE=(true|false)</dt>
 * <dd>Set the LogRecord source class and method for JavaUtilLog.<br>
 * Default: true
 * </dd>
 * <dt>org.eclipse.jetty.util.log.SOURCE=(true|false)</dt>
 * <dd>Set the LogRecord source class and method for all Loggers.<br>
 * Default: depends on Logger class
 * </dd>
 * </dl>
 */
public class TestingBotLogHandler extends AbstractLogger
{
    private static final String THIS_CLASS = TestingBotLogHandler.class.getName();
    private static final boolean __source =
        Boolean.parseBoolean(Log.getProperties().getProperty("org.eclipse.jetty.util.log.SOURCE",
            Log.getProperties().getProperty("org.eclipse.jetty.util.log.javautil.SOURCE", "true")));

    private static boolean _initialized = false;

    private Level configuredLevel;
    private java.util.logging.Logger _logger;

    public TestingBotLogHandler()
    {
        this("org.eclipse.jetty.util.log.javautil");
    }

    public TestingBotLogHandler(String name)
    {
        synchronized (TestingBotLogHandler.class)
        {
            if (!_initialized)
            {
                _initialized = true;
            }
        }

        _logger = java.util.logging.Logger.getLogger(name);

        _logger.setLevel(Level.WARNING);

        configuredLevel = _logger.getLevel();
    }

    @Override
    public String getName()
    {
        return _logger.getName();
    }

    protected void log(Level level, String msg, Throwable thrown)
    {
        LogRecord record = new LogRecord(level, msg);
        if (thrown != null)
            record.setThrown(thrown);
        record.setLoggerName(_logger.getName());
        if (__source)
        {
            StackTraceElement[] stack = new Throwable().getStackTrace();
            for (int i = 0; i < stack.length; i++)
            {
                StackTraceElement e = stack[i];
                if (!e.getClassName().equals(THIS_CLASS))
                {
                    record.setSourceClassName(e.getClassName());
                    record.setSourceMethodName(e.getMethodName());
                    break;
                }
            }
        }
        _logger.log(record);
    }

    @Override
    public void warn(String msg, Object... args)
    {
        if (_logger.isLoggable(Level.WARNING))
            log(Level.WARNING, format(msg, args), null);
    }

    @Override
    public void warn(Throwable thrown)
    {
        if (_logger.isLoggable(Level.WARNING))
            log(Level.WARNING, "", thrown);
    }

    @Override
    public void warn(String msg, Throwable thrown)
    {
        if (_logger.isLoggable(Level.WARNING))
            log(Level.WARNING, msg, thrown);
    }

    @Override
    public void info(String msg, Object... args)
    {
        if (_logger.isLoggable(Level.INFO))
            log(Level.INFO, format(msg, args), null);
    }

    @Override
    public void info(Throwable thrown)
    {
        if (_logger.isLoggable(Level.INFO))
            log(Level.INFO, "", thrown);
    }

    @Override
    public void info(String msg, Throwable thrown)
    {
        if (_logger.isLoggable(Level.INFO))
            log(Level.INFO, msg, thrown);
    }

    @Override
    public boolean isDebugEnabled()
    {
        return _logger.isLoggable(Level.FINE);
    }

    @Override
    public void setDebugEnabled(boolean enabled)
    {
        if (enabled)
        {
            configuredLevel = _logger.getLevel();
            _logger.setLevel(Level.FINE);
        }
        else
        {
            _logger.setLevel(configuredLevel);
        }
    }

    @Override
    public void debug(String msg, Object... args)
    {
        if (_logger.isLoggable(Level.FINE))
            log(Level.FINE, format(msg, args), null);
    }

    @Override
    public void debug(String msg, long arg)
    {
        if (_logger.isLoggable(Level.FINE))
            log(Level.FINE, format(msg, arg), null);
    }

    @Override
    public void debug(Throwable thrown)
    {
        if (_logger.isLoggable(Level.FINE))
            log(Level.FINE, "", thrown);
    }

    @Override
    public void debug(String msg, Throwable thrown)
    {
        if (_logger.isLoggable(Level.FINE))
            log(Level.FINE, msg, thrown);
    }

    /**
     * Create a Child Logger of this Logger.
     * @param fullname
     * @return Logger
     */
    @Override
    protected Logger newLogger(String fullname)
    {
        return new TestingBotLogHandler(fullname);
    }

    @Override
    public void ignore(Throwable ignored)
    {
        if (_logger.isLoggable(Level.FINEST))
            log(Level.FINEST, Log.IGNORED, ignored);
    }

    private String format(String msg, Object... args)
    {
        msg = String.valueOf(msg); // Avoids NPE
        String braces = "{}";
        StringBuilder builder = new StringBuilder();
        int start = 0;
        for (Object arg : args)
        {
            int bracesIndex = msg.indexOf(braces, start);
            if (bracesIndex < 0)
            {
                builder.append(msg.substring(start));
                builder.append(" ");
                builder.append(arg);
                start = msg.length();
            }
            else
            {
                builder.append(msg, start, bracesIndex);
                builder.append(arg);
                start = bracesIndex + braces.length();
            }
        }
        builder.append(msg.substring(start));
        return builder.toString();
    }
}