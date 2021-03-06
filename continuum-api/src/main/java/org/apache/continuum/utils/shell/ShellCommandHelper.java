package org.apache.continuum.utils.shell;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.shared.release.ReleaseResult;

import java.io.File;
import java.util.Map;
import java.util.Properties;

/**
 * @author <a href="mailto:trygvis@inamo.no">Trygve Laugst&oslash;l</a>
 */
public interface ShellCommandHelper
{
    String ROLE = ShellCommandHelper.class.getName();

    Properties getSystemEnvVars();

    ExecutionResult executeShellCommand( File workingDirectory, String executable, String arguments, File output,
                                         long idCommand, Map<String, String> environments )
        throws Exception;

    ExecutionResult executeShellCommand( File workingDirectory, String executable, String[] arguments, OutputConsumer io,
                                         long idCommand, Map<String, String> environments )
        throws Exception;

    ExecutionResult executeShellCommand( File workingDirectory, String executable, String[] arguments, File output,
                                         long idCommand, Map<String, String> environments )
        throws Exception;

    boolean isRunning( long idCommand );

    void killProcess( long idCommand );

    void executeGoals( File workingDirectory, String executable, String goals, boolean interactive, String arguments,
                       ReleaseResult relResult, Map<String, String> environments )
        throws Exception;

    void executeGoals( File workingDirectory, String executable, String goals, boolean interactive, String[] arguments,
                       ReleaseResult relResult, Map<String, String> environments )
        throws Exception;
}
