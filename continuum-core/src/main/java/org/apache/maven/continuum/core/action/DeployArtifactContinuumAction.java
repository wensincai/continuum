package org.apache.maven.continuum.core.action;

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

import org.apache.continuum.model.repository.LocalRepository;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.deployer.ArtifactDeployer;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryFactory;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.continuum.configuration.ConfigurationService;
import org.apache.maven.continuum.execution.ContinuumBuildExecutor;
import org.apache.maven.continuum.execution.manager.BuildExecutorManager;
import org.apache.maven.continuum.execution.maven.m2.MavenBuilderHelper;
import org.apache.maven.continuum.model.project.BuildDefinition;
import org.apache.maven.continuum.model.project.Project;
import org.apache.maven.continuum.project.ContinuumProjectState;
import org.apache.maven.continuum.utils.WorkingDirectoryService;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * @author <a href="mailto:brett@apache.org">Brett Porter</a>
 */
@Component( role = org.codehaus.plexus.action.Action.class, hint = "deploy-artifact" )
public class DeployArtifactContinuumAction
    extends AbstractContinuumAction
{

    @Requirement
    private ConfigurationService configurationService;

    @Requirement
    private BuildExecutorManager buildExecutorManager;

    @Requirement
    private WorkingDirectoryService workingDirectoryService;

    @Requirement
    private ArtifactDeployer artifactDeployer;

    @Requirement
    private MavenBuilderHelper builderHelper;

    @Requirement
    private ArtifactRepositoryFactory artifactRepositoryFactory;

    public void execute( Map context )
        throws Exception
    {
        // ----------------------------------------------------------------------
        // Get parameters from the context
        // ----------------------------------------------------------------------

        File deploymentRepositoryDirectory = configurationService.getDeploymentRepositoryDirectory();

        if ( deploymentRepositoryDirectory != null )
        {

            Project project = getProject( context );

            ContinuumBuildExecutor buildExecutor = buildExecutorManager.getBuildExecutor( project.getExecutorId() );

            // ----------------------------------------------------------------------
            // This is really a precondition for this action to execute
            // ----------------------------------------------------------------------

            if ( project.getState() == ContinuumProjectState.OK )
            {
                BuildDefinition buildDefinition = getBuildDefinition( context );

                String projectScmRootUrl = getProjectScmRootUrl( context, project.getScmUrl() );

                List<Project> projectsWithCommonScmRoot = getListOfProjectsInGroupWithCommonScmRoot( context );

                List<Artifact> artifacts = buildExecutor.getDeployableArtifacts( project,
                                                                                 workingDirectoryService.getWorkingDirectory(
                                                                                     project, projectScmRootUrl,
                                                                                     projectsWithCommonScmRoot ),
                                                                                 buildDefinition );

                LocalRepository repository = project.getProjectGroup().getLocalRepository();
                ArtifactRepository localRepository = builderHelper.getLocalRepository( repository );

                for ( Artifact artifact : artifacts )
                {
                    ArtifactRepositoryLayout repositoryLayout = new DefaultRepositoryLayout();

                    if ( !deploymentRepositoryDirectory.exists() )
                    {
                        deploymentRepositoryDirectory.mkdirs();
                    }

                    String location = deploymentRepositoryDirectory.toURL().toExternalForm();

                    ArtifactRepository deploymentRepository =
                        artifactRepositoryFactory.createDeploymentArtifactRepository( "deployment-repository", location,
                                                                                      repositoryLayout, true );

                    artifactDeployer.deploy( artifact.getFile(), artifact, deploymentRepository, localRepository );
                }
            }
        }
    }
}
