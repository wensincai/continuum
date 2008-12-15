package org.apache.continuum.buildmanager;

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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.ArrayUtils;
import org.apache.continuum.dao.BuildDefinitionDao;
import org.apache.continuum.taskqueue.OverallBuildQueue;
import org.apache.maven.continuum.buildqueue.BuildProjectTask;
import org.apache.maven.continuum.configuration.ConfigurationService;
import org.apache.maven.continuum.model.project.BuildDefinition;
import org.apache.maven.continuum.model.project.BuildQueue;
import org.apache.maven.continuum.model.project.Project;
import org.apache.maven.continuum.scm.queue.CheckOutTask;
import org.apache.maven.continuum.scm.queue.PrepareBuildProjectsTask;
import org.apache.maven.continuum.store.ContinuumStoreException;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLifecycleException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Contextualizable;
import org.codehaus.plexus.taskqueue.Task;
import org.codehaus.plexus.taskqueue.TaskQueue;
import org.codehaus.plexus.taskqueue.TaskQueueException;
import org.codehaus.plexus.taskqueue.execution.ThreadedTaskQueueExecutor;
import org.codehaus.plexus.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parallel builds manager. 
 * 
 * @author <a href="mailto:oching@apache.org">Maria Odea Ching</a>
 * @plexus.component role="org.apache.continuum.buildmanager.BuildsManager" role-hint="parallel"
 */
public class ParallelBuildsManager
    implements BuildsManager, Contextualizable
{
    private Logger log = LoggerFactory.getLogger( ParallelBuildsManager.class );

    // map must be synchronized!
    private Map<Integer, OverallBuildQueue> overallBuildQueues =
        Collections.synchronizedMap( new HashMap<Integer, OverallBuildQueue>() );

    private static final int BUILD_QUEUE = 1;

    private static final int CHECKOUT_QUEUE = 2;

    /**
     * @plexus.requirement
     */
    private BuildDefinitionDao buildDefinitionDao;

    /**
     * @plexus.requirement role-hint="prepare-build-project"
     */
    private TaskQueue prepareBuildQueue;

    /**
     * @plexus.requirement
     */
    private ConfigurationService configurationService;

    private PlexusContainer container;

    public void buildProject( int projectId, BuildDefinition buildDefinition, String projectName, int trigger )
        throws BuildManagerException
    {
        try
        {
            if ( isInQueue( projectId, BUILD_QUEUE, -1 ) )
            {
                log.warn( "Project already queued." );
                return;
            }
        }
        catch ( TaskQueueException e )
        {
            throw new BuildManagerException( "Error occurred while checking if the project is already in queue: " +
                e.getMessage() );
        }

        OverallBuildQueue overallBuildQueue =
            getOverallBuildQueue( projectId, BUILD_QUEUE, buildDefinition.getSchedule().getBuildQueues() );

        String buildDefinitionLabel = buildDefinition.getDescription();
        if ( StringUtils.isEmpty( buildDefinitionLabel ) )
        {
            buildDefinitionLabel = buildDefinition.getGoals();
        }

        Task buildTask =
            new BuildProjectTask( projectId, buildDefinition.getId(), trigger, projectName, buildDefinitionLabel );
        try
        {
            log.info( "Queueing project '" + projectId + "' in build queue '" + overallBuildQueue.getName() );
            overallBuildQueue.addToBuildQueue( buildTask );
        }
        catch ( TaskQueueException e )
        {
            throw new BuildManagerException( "Error occurred while adding project to build queue: " + e.getMessage() );
        }
    }

    public void buildProjects( List<Project> projects, Map<Integer, BuildDefinition> projectsBuildDefinitionsMap,
                               int trigger )
        throws BuildManagerException
    {
        int firstProjectId = 0;
        // get id of the first project in the list that is not yet in the build queue
        for ( Project project : projects )
        {
            try
            {
                if ( !isInQueue( project.getId(), BUILD_QUEUE, -1 ) )
                {
                    firstProjectId = project.getId();
                    break;
                }
            }
            catch ( TaskQueueException e )
            {
                log.warn( "Error occurred while verifying if project is already queued." );
                continue;
            }
        }

        if ( firstProjectId != 0 )
        {
            BuildDefinition buildDef = projectsBuildDefinitionsMap.get( firstProjectId );
            OverallBuildQueue overallBuildQueue =
                getOverallBuildQueue( firstProjectId, BUILD_QUEUE, buildDef.getSchedule().getBuildQueues() );
            ;

            if ( overallBuildQueue != null )
            {
                for ( Project project : projects )
                {
                    try
                    {
                        if ( isInQueue( project.getId(), BUILD_QUEUE, -1 ) )
                        {
                            continue;
                        }
                    }
                    catch ( TaskQueueException e )
                    {
                        log.warn( "Error occurred while verifying if project is already queued." );
                        continue;
                    }

                    BuildDefinition buildDefinition = projectsBuildDefinitionsMap.get( project.getId() );
                    String buildDefinitionLabel = buildDefinition.getDescription();
                    if ( StringUtils.isEmpty( buildDefinitionLabel ) )
                    {
                        buildDefinitionLabel = buildDefinition.getGoals();
                    }

                    BuildProjectTask buildTask =
                        new BuildProjectTask( project.getId(), buildDefinition.getId(), trigger, project.getName(),
                                              buildDefinitionLabel );
                    buildTask.setMaxExecutionTime( buildDefinition.getSchedule().getMaxJobExecutionTime() * 1000 );

                    try
                    {
                        overallBuildQueue.addToBuildQueue( buildTask );
                    }
                    catch ( TaskQueueException e )
                    {
                        throw new BuildManagerException( "Error occurred while adding project to build queue: " +
                            e.getMessage() );
                    }
                }
            }
        }
        else
        {
            log.error( "Projects are already in build queue." );
            throw new BuildManagerException( "Projects are already in build queue." );
        }
    }

    public boolean cancelBuildInQueue( int buildQueueId )
        throws BuildManagerException
    {
        synchronized ( overallBuildQueues )
        {
            OverallBuildQueue overallBuildQueue = null;
            try
            {
                overallBuildQueue = overallBuildQueues.get( buildQueueId );
                if ( overallBuildQueue != null )
                {
                    overallBuildQueue.cancelCurrentBuild();
                }
                else
                {
                    log.warn( "Project not found in any of the build queues." );
                }
            }
            catch ( ComponentLookupException e )
            {
                log.error( e.getMessage() );
                throw new BuildManagerException( e.getMessage() );
            }

            return true;
        }
    }

    public boolean cancelAllBuilds()
        throws BuildManagerException
    {
        synchronized ( overallBuildQueues )
        {
            Set<Integer> keySet = overallBuildQueues.keySet();
            OverallBuildQueue overallBuildQueue = null;

            try
            {
                for ( Integer key : keySet )
                {
                    overallBuildQueue = overallBuildQueues.get( key );
                    overallBuildQueue.cancelCurrentBuild();
                }
            }
            catch ( ComponentLookupException e )
            {
                log.error( e.getMessage() );
                throw new BuildManagerException( e.getMessage() );
            }

            return true;
        }
    }

    public boolean cancelAllCheckouts()
        throws BuildManagerException
    {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean cancelBuild( int projectId )
        throws BuildManagerException
    {
        try
        {
            OverallBuildQueue overallBuildQueue = getOverallBuildQueueWhereProjectIsQueued( projectId, BUILD_QUEUE );
            if ( overallBuildQueue != null )
            {
                overallBuildQueue.cancelBuildTask( projectId );
            }
            else
            {
                log.info( "Project '" + projectId + "' not found in any of the builds queues." );
                //throw new BuildManagerException( "Project not found in any of the build queues." );
            }
        }
        catch ( TaskQueueException e )
        {
            throw new BuildManagerException( "Error occurred while cancelling build: " + e.getMessage() );
        }
        catch ( ComponentLookupException e )
        {
            throw new BuildManagerException( e.getMessage() );
        }

        return true;
    }

    public boolean cancelCheckout( int projectId )
        throws BuildManagerException
    {
        // TODO: should this be permitted? (might need to execute svn cleanup?)

        try
        {
            OverallBuildQueue overallBuildQueue = getOverallBuildQueueWhereProjectIsQueued( projectId, CHECKOUT_QUEUE );
            if ( overallBuildQueue != null )
            {
                //overallBuildQueue.getCheckoutQueue()
            }
            else
            {
                throw new BuildManagerException( "Project not found in any of the checkout queues." );
            }
        }
        catch ( TaskQueueException e )
        {
            throw new BuildManagerException( "Error occurred while cancelling build: " + e.getMessage() );
        }

        return true;
    }

    public void checkoutProject( int projectId, String projectName, File workingDirectory, String scmUsername,
                                 String scmPassword, BuildDefinition defaultBuildDefinition )
        throws BuildManagerException
    {
        try
        {
            if ( isInQueue( projectId, CHECKOUT_QUEUE, -1 ) )
            {
                log.warn( "Project already in checkout queue." );
                return;
            }
        }
        catch ( TaskQueueException e )
        {
            throw new BuildManagerException( "Error occurred while checking if the project is already in queue: " +
                e.getMessage() );
        }

        OverallBuildQueue overallBuildQueue =
            getOverallBuildQueue( projectId, CHECKOUT_QUEUE, defaultBuildDefinition.getSchedule().getBuildQueues() );

        CheckOutTask checkoutTask =
            new CheckOutTask( projectId, workingDirectory, projectName, scmUsername, scmPassword );
        try
        {
            overallBuildQueue.addToCheckoutQueue( checkoutTask );
        }
        catch ( TaskQueueException e )
        {
            throw new BuildManagerException( "Error occurred while adding project to checkout queue: " + e.getMessage() );
        }
    }

    public boolean isInAnyBuildQueue( int projectId )
        throws BuildManagerException
    {
        try
        {
            return isInQueue( projectId, BUILD_QUEUE, -1 );
        }
        catch ( TaskQueueException e )
        {
            throw new BuildManagerException( e.getMessage() );
        }
    }

    public boolean isInAnyBuildQueue( int projectId, int buildDefinitionId )
        throws BuildManagerException
    {
        try
        {
            return isInQueue( projectId, BUILD_QUEUE, buildDefinitionId );
        }
        catch ( TaskQueueException e )
        {
            throw new BuildManagerException( e.getMessage() );
        }
    }

    public boolean isInAnyCheckoutQueue( int projectId )
        throws BuildManagerException
    {
        try
        {
            return isInQueue( projectId, CHECKOUT_QUEUE, -1 );
        }
        catch ( TaskQueueException e )
        {
            throw new BuildManagerException( e.getMessage() );
        }
    }

    public boolean isInPrepareBuildQueue( int projectId )
        throws BuildManagerException
    {
        try
        {
            List<PrepareBuildProjectsTask> queue = prepareBuildQueue.getQueueSnapshot();
            for ( PrepareBuildProjectsTask task : queue )
            {
                if ( task != null )
                {
                    Map<Integer, Integer> map = ( (PrepareBuildProjectsTask) task ).getProjectsBuildDefinitionsMap();

                    if ( map.size() > 0 )
                    {
                        Set<Integer> projectIds = map.keySet();

                        if ( projectIds.contains( new Integer( projectId ) ) )
                        {
                            return true;
                        }
                    }
                }
            }
        }
        catch ( TaskQueueException e )
        {
            throw new BuildManagerException( e.getMessage() );
        }

        return false;
    }

    public boolean isProjectInAnyCurrentBuild( int projectId )
        throws BuildManagerException
    {
        try
        {
            List<Object> objects = container.lookupList( ThreadedTaskQueueExecutor.class );
            for ( Object obj : objects )
            {
                log.info( "Task queue executor : " + obj );
                ThreadedTaskQueueExecutor executor = (ThreadedTaskQueueExecutor) obj;
                Task task = executor.getCurrentTask();
                if ( task instanceof BuildProjectTask )
                {
                    log.info( "current task is a BuildProjectTask." );
                    return true;
                }
            }
        }
        catch ( ComponentLookupException e )
        {
            throw new BuildManagerException( e.getMessage() );
        }

        return false;
    }

    public void prepareBuildProject( Map<Integer, Integer> projectsBuildDefinitionsMap, int trigger )
        throws BuildManagerException
    {
        try
        {
            PrepareBuildProjectsTask task = new PrepareBuildProjectsTask( projectsBuildDefinitionsMap, trigger );
            prepareBuildQueue.put( task );
        }
        catch ( TaskQueueException e )
        {
            throw new BuildManagerException( "Error occurred while creating prepare-build-project task: " +
                e.getMessage() );
        }
    }

    public void removeProjectFromBuildQueue( int projectId )
        throws BuildManagerException
    {
        try
        {
            OverallBuildQueue overallBuildQueue = getOverallBuildQueueWhereProjectIsQueued( projectId, BUILD_QUEUE );
            if ( overallBuildQueue != null )
            {
                overallBuildQueue.removeProjectFromBuildQueue( projectId );
            }
            else
            {
                log.info( "Project '" + projectId + "' not found in any of the build queues." );
                //throw new BuildManagerException( "Project not found in any of the build queues." );
            }
        }
        catch ( TaskQueueException e )
        {
            throw new BuildManagerException( "Error occurred while removing project from build queue: " +
                e.getMessage() );
        }
    }

    public void removeProjectFromBuildQueue( int projectId, int buildDefinitionId, int trigger, String projectName )
        throws BuildManagerException
    {
        try
        {
            OverallBuildQueue overallBuildQueue = getOverallBuildQueueWhereProjectIsQueued( projectId, BUILD_QUEUE );
            if ( overallBuildQueue != null )
            {
                overallBuildQueue.removeProjectFromBuildQueue( projectId, buildDefinitionId, trigger, projectName );
            }
            else
            {
                log.info( "Project '" + projectId + "' not found in any of the build queues." );
                //throw new BuildManagerException( "Project not found in any of the build queues." );
            }
        }
        catch ( TaskQueueException e )
        {
            throw new BuildManagerException( "Error occurred while removing project from build queue: " +
                e.getMessage() );
        }
    }

    public void removeProjectFromCheckoutQueue( int projectId )
        throws BuildManagerException
    {
        try
        {
            OverallBuildQueue overallBuildQueue = getOverallBuildQueueWhereProjectIsQueued( projectId, CHECKOUT_QUEUE );
            if ( overallBuildQueue != null )
            {
                overallBuildQueue.removeProjectFromCheckoutQueue( projectId );
            }
            else
            {
                log.info( "Project '" + projectId + "' not found in any of the checkout queues." );
                //throw new BuildManagerException( "Project not found in any of the checkout queues." );
            }
        }
        catch ( TaskQueueException e )
        {
            throw new BuildManagerException( "Error occurred while removing project from checkout queue: " +
                e.getMessage() );
        }
    }

    public void removeProjectsFromBuildQueue( int[] projectIds )
    {
        for ( int i = 0; i < projectIds.length; i++ )
        {
            try
            {
                OverallBuildQueue overallBuildQueue =
                    getOverallBuildQueueWhereProjectIsQueued( projectIds[i], BUILD_QUEUE );
                if ( overallBuildQueue != null )
                {
                    overallBuildQueue.removeProjectFromBuildQueue( projectIds[i] );
                }
                else
                {
                    log.error( "Project '" + projectIds[i] + "' not found in any of the build queues." );
                    continue;
                }
            }
            catch ( TaskQueueException e )
            {
                log.error( "Error occurred while removing project '" + projectIds[i] + "' from build queue." );
                continue;
            }
        }
    }

    public void removeProjectsFromCheckoutQueue( int[] projectIds )
    {
        for ( int i = 0; i < projectIds.length; i++ )
        {
            try
            {
                OverallBuildQueue overallBuildQueue =
                    getOverallBuildQueueWhereProjectIsQueued( projectIds[i], CHECKOUT_QUEUE );
                if ( overallBuildQueue != null )
                {
                    overallBuildQueue.removeProjectFromCheckoutQueue( projectIds[i] );
                }
                else
                {
                    log.error( "Project '" + projectIds[i] + "' not found in any of the checkout queues." );
                    continue;
                }
            }
            catch ( TaskQueueException e )
            {
                log.error( "Error occurred while removing project '" + projectIds[i] + "' from checkout queue." );
                continue;
            }
        }
    }

    public void removeProjectsFromCheckoutQueueWithHashcodes( int[] hashcodes )
        throws BuildManagerException
    {
        try
        {
            synchronized ( overallBuildQueues )
            {
                Set<Integer> keySet = overallBuildQueues.keySet();
                for ( Integer key : keySet )
                {
                    OverallBuildQueue overallBuildQueue = overallBuildQueues.get( key );
                    overallBuildQueue.removeTasksFromCheckoutQueueWithHashCodes( hashcodes );
                    /*List<CheckOutTask> tasks = overallBuildQueue.getCheckOutTasksInQueue();
                    for ( CheckOutTask task : tasks )
                    {
                        if ( ArrayUtils.contains( hashcodes, task.hashCode() ) )
                        {
                            overallBuildQueue.getCheckoutQueue().remove( task );
                        }
                    }*/
                }
            }
        }
        catch ( TaskQueueException e )
        {
            throw new BuildManagerException( "Error encountered while removing project(s) from checkout queue.", e );
        }
    }

    public void removeProjectsFromBuildQueueWithHashcodes( int[] hashcodes )
        throws BuildManagerException
    {
        try
        {
            synchronized ( overallBuildQueues )
            {
                Set<Integer> keySet = overallBuildQueues.keySet();
                for ( Integer key : keySet )
                {
                    OverallBuildQueue overallBuildQueue = overallBuildQueues.get( key );
                    overallBuildQueue.removeProjectsFromBuildQueueWithHashCodes( hashcodes );
                    /*List<Task> tasks = overallBuildQueue.getProjectsInBuildQueue();
                    for ( Task task : tasks )
                    {
                        if ( ArrayUtils.contains( hashcodes, task.hashCode() ) )
                        {
                            overallBuildQueue.getBuildQueue().remove( task );
                        }
                    }*/
                }
            }
        }
        catch ( TaskQueueException e )
        {
            throw new BuildManagerException( "Error encountered while removing project(s) from build queue.", e );
        }
    }

    public void addOverallBuildQueue( OverallBuildQueue overallBuildQueue )
    {
        // set the container which is used by overall build queue for getting the task queue executor
        // trying to avoid implementing Contextualizable for the OverallBuildQueue! 
        overallBuildQueue.setContainer( container );

        synchronized ( overallBuildQueues )
        {
            if ( overallBuildQueues.get( overallBuildQueue.getId() ) == null )
            {
                this.overallBuildQueues.put( overallBuildQueue.getId(), overallBuildQueue );
            }
            else
            {
                log.warn( "Overall build queue already in the map" );
            }
        }
    }

    public void removeOverallBuildQueue( int overallBuildQueueId )
        throws BuildManagerException
    {
        List<Task> tasks = null;
        List<CheckOutTask> checkoutTasks = null;

        /*if( overallBuildQueueId == 1 )
        {
            throw new BuildManagerException( "Default build queue cannot be deleted." );
        }*/

        synchronized ( overallBuildQueues )
        {
            OverallBuildQueue overallBuildQueue = overallBuildQueues.get( overallBuildQueueId );
            if ( overallBuildQueue.getName().equals( ConfigurationService.DEFAULT_BUILD_QUEUE_NAME ) )
            {
                throw new BuildManagerException( "Cannot remove default build queue." );
            }

            try
            {
                tasks = overallBuildQueue.getProjectsInBuildQueue();
                checkoutTasks = overallBuildQueue.getCheckOutTasksInQueue();

                overallBuildQueue.getBuildQueue().removeAll( tasks );
                overallBuildQueue.getCheckoutQueue().removeAll( checkoutTasks );

                container.release( overallBuildQueue );
            }
            catch ( TaskQueueException e )
            {
                throw new BuildManagerException(
                                                 "Cannot remove build queue. An error occurred while retrieving queued tasks." );
            }
            catch ( ComponentLifecycleException e )
            {
                throw new BuildManagerException(
                                                 "Cannot remove build queue. An error occurred while destroying the build queue: " +
                                                     e.getMessage() );
            }

            this.overallBuildQueues.remove( overallBuildQueueId );
        }

        try
        {
            for ( Task task : tasks )
            {
                BuildProjectTask buildTask = (BuildProjectTask) task;
                BuildDefinition buildDefinition =
                    buildDefinitionDao.getBuildDefinition( buildTask.getBuildDefinitionId() );
                buildProject( buildTask.getProjectId(), buildDefinition, buildTask.getProjectName(),
                              buildTask.getTrigger() );
            }

            for ( CheckOutTask task : checkoutTasks )
            {
                BuildDefinition buildDefinition = buildDefinitionDao.getDefaultBuildDefinition( task.getProjectId() );
                checkoutProject( task.getProjectId(), task.getProjectName(), task.getWorkingDirectory(),
                                 task.getScmUserName(), task.getScmPassword(), buildDefinition );
            }
        }
        catch ( ContinuumStoreException e )
        {
            throw new BuildManagerException( "Cannot remove build queue: " + e.getMessage() );
        }
    }

    public Map<Integer, OverallBuildQueue> getOverallBuildQueues()
    {
        return overallBuildQueues;
    }

    public List<Task> getCurrentBuilds()
        throws BuildManagerException
    {
        List<Task> currentBuilds = new ArrayList<Task>();
        try
        {
            List<Object> objects = container.lookupList( ThreadedTaskQueueExecutor.class );
            for ( Object obj : objects )
            {
                log.info( "Task queue executor : " + obj );
                ThreadedTaskQueueExecutor executor = (ThreadedTaskQueueExecutor) obj;
                Task task = executor.getCurrentTask();
                if ( task instanceof BuildProjectTask )
                {
                    currentBuilds.add( task );
                }
            }
        }
        catch ( ComponentLookupException e )
        {
            throw new BuildManagerException( e.getMessage() );
        }

        return currentBuilds;
    }

    public List<Task> getCurrentCheckouts()
        throws BuildManagerException
    {
        List<Task> currentCheckouts = new ArrayList<Task>();
        try
        {
            List<Object> objects = container.lookupList( ThreadedTaskQueueExecutor.class );
            for ( Object obj : objects )
            {
                ThreadedTaskQueueExecutor executor = (ThreadedTaskQueueExecutor) obj;
                Task task = executor.getCurrentTask();
                if ( task instanceof CheckOutTask )
                {
                    currentCheckouts.add( task );
                }
            }
        }
        catch ( ComponentLookupException e )
        {
            throw new BuildManagerException( e.getMessage() );
        }

        return currentCheckouts;
    }

    public Map<String, List<Task>> getProjectsInBuildQueues()
        throws BuildManagerException
    {
        synchronized ( overallBuildQueues )
        {
            Map<String, List<Task>> buildsInQueue = new HashMap<String, List<Task>>();
            Set<Integer> keySet = overallBuildQueues.keySet();
            for ( Integer key : keySet )
            {
                OverallBuildQueue overallBuildQueue = overallBuildQueues.get( key );
                try
                {
                    buildsInQueue.put( overallBuildQueue.getName(), overallBuildQueue.getProjectsInBuildQueue() );
                }
                catch ( TaskQueueException e )
                {
                    throw new BuildManagerException( "Error occurred while getting projects in build queue '" +
                        overallBuildQueue.getName() + "'.", e );
                }
            }
            return buildsInQueue;
        }
    }

    public Map<String, List<Task>> getProjectsInCheckoutQueues()
        throws BuildManagerException
    {
        synchronized ( overallBuildQueues )
        {
            Map<String, List<Task>> checkoutsInQueue = new HashMap<String, List<Task>>();
            Set<Integer> keySet = overallBuildQueues.keySet();
            for ( Integer key : keySet )
            {
                OverallBuildQueue overallBuildQueue = overallBuildQueues.get( key );
                try
                {
                    checkoutsInQueue.put( overallBuildQueue.getName(), overallBuildQueue.getCheckOutTasksInQueue() );
                }
                catch ( TaskQueueException e )
                {
                    throw new BuildManagerException( "Error occurred while getting projects in checkout queue '" +
                        overallBuildQueue.getName() + "'.", e );
                }
            }
            return checkoutsInQueue;
        }
    }

    public boolean isBuildInProgress()
        throws BuildManagerException
    {
        return false;
    }

    private boolean isInQueue( int projectId, int typeOfQueue, int buildDefinitionId )
        throws TaskQueueException
    {
        synchronized ( overallBuildQueues )
        {
            Set<Integer> keySet = overallBuildQueues.keySet();
            for ( Integer key : keySet )
            {
                OverallBuildQueue overallBuildQueue = overallBuildQueues.get( key );
                if ( typeOfQueue == BUILD_QUEUE )
                {
                    if ( buildDefinitionId < 0 )
                    {
                        if ( overallBuildQueue.isInBuildQueue( projectId ) )
                        {
                            return true;
                        }
                    }
                    else
                    {
                        if ( overallBuildQueue.isInBuildQueue( projectId, buildDefinitionId ) )
                        {
                            return true;
                        }
                    }
                }
                else if ( typeOfQueue == CHECKOUT_QUEUE )
                {
                    if ( overallBuildQueue.isInCheckoutQueue( projectId ) )
                    {
                        return true;
                    }
                }
            }

            return false;
        }
    }

    // get overall queue where project is queued
    private OverallBuildQueue getOverallBuildQueueWhereProjectIsQueued( int projectId, int typeOfQueue )
        throws TaskQueueException
    {
        synchronized ( overallBuildQueues )
        {
            OverallBuildQueue whereQueued = null;
            Set<Integer> keySet = overallBuildQueues.keySet();

            for ( Integer key : keySet )
            {
                OverallBuildQueue overallBuildQueue = overallBuildQueues.get( key );
                if ( typeOfQueue == BUILD_QUEUE )
                {
                    if ( overallBuildQueue.isInBuildQueue( projectId ) )
                    {
                        whereQueued = overallBuildQueue;
                        break;
                    }
                }
                else if ( typeOfQueue == CHECKOUT_QUEUE )
                {
                    if ( overallBuildQueue.isInCheckoutQueue( projectId ) )
                    {
                        whereQueued = overallBuildQueue;
                        break;
                    }
                }
            }

            return whereQueued;
        }
    }

    // get overall queue where project will be queued
    private OverallBuildQueue getOverallBuildQueue( int projectId, int typeOfQueue, List<BuildQueue> buildQueues )
        throws BuildManagerException
    {
        OverallBuildQueue whereToBeQueued = null;
        synchronized ( overallBuildQueues )
        {
            if ( overallBuildQueues == null || overallBuildQueues.isEmpty() )
            {
                throw new BuildManagerException( "No build queues configured." );
            }

            int size = 0;
            int idx = 0;
            try
            {
                for ( BuildQueue buildQueue : buildQueues )
                {
                    OverallBuildQueue overallBuildQueue = overallBuildQueues.get( buildQueue.getId() );
                    if ( overallBuildQueue != null )
                    {
                        TaskQueue taskQueue = null;
                        if ( typeOfQueue == BUILD_QUEUE )
                        {
                            taskQueue = overallBuildQueue.getBuildQueue();
                        }
                        else if ( typeOfQueue == CHECKOUT_QUEUE )
                        {
                            taskQueue = overallBuildQueue.getCheckoutQueue();
                        }

                        if ( idx == 0 )
                        {
                            size = taskQueue.getQueueSnapshot().size();
                            whereToBeQueued = overallBuildQueue;
                        }

                        if ( taskQueue.getQueueSnapshot().size() < size )
                        {
                            whereToBeQueued = overallBuildQueue;
                            size = taskQueue.getQueueSnapshot().size();
                        }

                        idx++;
                    }
                    else
                    {
                        log.error( "Build queue not found." );
                    }
                }
            }
            catch ( TaskQueueException e )
            {
                throw new BuildManagerException( "Error occurred while retrieving task quueue: " + e.getMessage() );
            }
        }

        if ( whereToBeQueued == null )
        {
            // TODO queue in the default overall build queue
            throw new BuildManagerException( "No build queue found." );
        }

        return whereToBeQueued;
    }

    public void setBuildDefinitionDao( BuildDefinitionDao buildDefinitionDao )
    {
        this.buildDefinitionDao = buildDefinitionDao;
    }

    public void contextualize( Context context )
        throws ContextException
    {
        container = (PlexusContainer) context.get( PlexusConstants.PLEXUS_KEY );

        synchronized ( overallBuildQueues )
        {
            try
            {
                BuildQueue defaultBuildQueue = configurationService.getDefaultBuildQueue();

                OverallBuildQueue defaultOverallBuildQueue =
                    (OverallBuildQueue) container.lookup( OverallBuildQueue.class );
                defaultOverallBuildQueue.setId( defaultBuildQueue.getId() );
                defaultOverallBuildQueue.setName( defaultBuildQueue.getName() );
                defaultOverallBuildQueue.setContainer( container );

                overallBuildQueues.put( defaultOverallBuildQueue.getId(), defaultOverallBuildQueue );
            }
            catch ( ComponentLookupException e )
            {
                log.error( "Cannot add default build queue: " + e.getMessage() );
            }
            catch ( ContinuumStoreException e )
            {
                log.error( "Cannot add default build queue: " + e.getMessage() );
            }
        }
    }

    public void setContainer( PlexusContainer container )
    {
        this.container = container;
    }
}
