package org.apache.continuum.distributed.manager;

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

import java.util.List;
import java.util.Map;

import org.apache.continuum.distributed.BuildAgent;
import org.apache.maven.continuum.ContinuumException;

/**
 * @author Maria Catherine Tan
 */
public interface DistributedBuildManager
{
    String ROLE = DistributedBuildManager.class.getName();

    void reload();

    void buildProjects( Map<Integer, Integer> projectsAndBuildDefinitionsMap, int trigger )
        throws ContinuumException;

    void buildProjectsInQueue()
        throws ContinuumException;

    void updateProjectScmRoot( Map context )
        throws ContinuumException;

    void updateBuildResult( Map context )
        throws ContinuumException;

    List<BuildAgent> getBuildAgents();
}
