/*
 * $HeadURL$
 * $Date$
 * $Revision$
 *
 * Copyright (c) 2015 Cleverlance Enterprise Solutions a.s.
 * http://www.cleverlance.com
 * All Rights Reserved.
 */
package org.codehaus.mojo.versions;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLStreamException;

import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.release.versions.VersionParseException;
import org.codehaus.mojo.versions.api.PomHelper;
import org.codehaus.mojo.versions.change.VersionChange;
import org.codehaus.mojo.versions.change.VersionChanger;
import org.codehaus.mojo.versions.change.VersionChangerFactory;
import org.codehaus.mojo.versions.ordering.ReactorDepthComparator;
import org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader;
import org.codehaus.mojo.versions.utils.ContextualLog;
import org.codehaus.mojo.versions.utils.DelegatingContextualLog;
import org.codehaus.plexus.util.StringUtils;

/**
 * Abstract base class for Set Mojos.
 *
 * @author Pete Cornish
 * @since 2.0
 */
public abstract class AbstractVersionsSetMojo extends AbstractVersionsUpdaterMojo {

    /**
     * The groupId of the dependency/module to update.
     *
     * @parameter expression="${groupId}" default-value="${project.groupId}"
     * @since 1.2
     */
    private String groupId;

    /**
     * The artifactId of the dependecy/module to update.
     *
     * @parameter expression="${artifactId}" default-value="${project.artifactId}"
     * @since 1.2
     */
    private String artifactId;

    /**
     * Whether matching versions explicitly specified (as /project/version) in child modules should be updated.
     *
     * @parameter expression="${updateMatchingVersions}" default-value="true"
     * @since 1.3
     */
    private Boolean updateMatchingVersions;

    /**
     * Whether to process the parent of the project. If not set will default to true.
     *
     * @parameter expression="${processParent}" default-value="true"
     * @since 1.3
     */
    private boolean processParent;

    /**
     * Whether to process the project version. If not set will default to true.
     *
     * @parameter expression="${processProject}" default-value="true"
     * @since 1.3
     */
    private boolean processProject;

    /**
     * Whether to process the dependencies section of the project. If not set will default to true.
     *
     * @parameter expression="${processDependencies}" default-value="true"
     * @since 1.3
     */
    private boolean processDependencies;

    /**
     * Whether to process the plugins section of the project. If not set will default to true.
     *
     * @parameter expression="${processPlugins}" default-value="true"
     * @since 1.3
     */
    private boolean processPlugins;

    /**
     * The changes to module coordinates. Guarded by this.
     */
    private final transient List sourceChanges = new ArrayList();

    private synchronized void addChange( String groupId, String artifactId, String oldVersion, String newVersion )
    {
        if ( !newVersion.equals( oldVersion ) )
        {
            sourceChanges.add( new VersionChange( groupId, artifactId, oldVersion, newVersion ) );
        }
    }

    /**
     * Called when this mojo is executed.
     *
     * @throws org.apache.maven.plugin.MojoExecutionException
     *          when things go wrong.
     * @throws org.apache.maven.plugin.MojoFailureException
     *          when things go wrong.
     */
    public void execute()
            throws MojoExecutionException, MojoFailureException
    {

        if ( getProject().getOriginalModel().getVersion() == null )
        {
            throw new MojoExecutionException( "Project version is inherited from parent." );
        }

        // obtain new version to set
        String newVersion = null;
        try {
            newVersion = getNewVersion();
        } catch (VersionParseException e) {
            throw new MojoExecutionException( e.getMessage(), e );
        }

        // validate it
        if ( StringUtils.isEmpty( newVersion ) )
        {
            throw new MojoExecutionException( "You must specify the new version, either by using the newVersion "
                    + "property (that is -DnewVersion=... on the command line) or run in interactive mode" );
        }

        // set it
        setVersion(newVersion);
    }

    protected abstract String getNewVersion() throws MojoExecutionException, VersionParseException;

    protected void setVersion(String newVersion) throws MojoFailureException, MojoExecutionException {
        // store newVersion for another possible goal
        VersionStore.setVersion(newVersion);

        if (updateMatchingVersions == null) {
            updateMatchingVersions = Boolean.TRUE;
        }
        // this is the triggering change
        addChange( groupId, artifactId, getOldVersion(), newVersion );

        try
        {
            final MavenProject project =
                    PomHelper.getLocalRoot(projectBuilder, getProject(), localRepository, null, getLog());

            getLog().info( "Local aggregation root: " + project.getBasedir() );
            final Map<String, Model> reactor = PomHelper.getReactorModels( project, getLog() );

            // now fake out the triggering change
            final Model current =
                    PomHelper.getModel( reactor, getProject().getGroupId(), getProject().getArtifactId() );
            current.setVersion( newVersion );

            final Set<File> files = new LinkedHashSet<File>();
            files.add( getProject().getFile() );

            final List<String> order = new ArrayList<String>( reactor.keySet() );
            Collections.sort(order, new ReactorDepthComparator(reactor));

            final Iterator i = order.iterator();
            while ( i.hasNext() )
            {
                final String sourcePath = (String) i.next();
                final Model sourceModel = (Model) reactor.get( sourcePath );

                getLog().debug( sourcePath.length() == 0
                        ? "Processing root module as parent"
                        : "Processing " + sourcePath + " as a parent." );

                final String sourceGroupId = PomHelper.getGroupId( sourceModel );
                if ( sourceGroupId == null )
                {
                    getLog().warn( "Module " + sourcePath + " is missing a groupId." );
                    continue;
                }
                final String sourceArtifactId = PomHelper.getArtifactId( sourceModel );
                if ( sourceArtifactId == null )
                {
                    getLog().warn( "Module " + sourcePath + " is missing an artifactId." );
                    continue;
                }
                final String sourceVersion = PomHelper.getVersion( sourceModel );
                if ( sourceVersion == null )
                {
                    getLog().warn( "Module " + sourcePath + " is missing a version." );
                    continue;
                }

                final File moduleDir = new File( project.getBasedir(), sourcePath );

                final File moduleProjectFile;

                if ( moduleDir.isDirectory() )
                {
                    moduleProjectFile = new File( moduleDir, "pom.xml" );
                }
                else
                {
                    // i don't think this should ever happen... but just in case
                    // the module references the file-name
                    moduleProjectFile = moduleDir;
                }

                files.add( moduleProjectFile );

                getLog().debug(
                        "Looking for modules which use " + ArtifactUtils.versionlessKey(sourceGroupId, sourceArtifactId)
                                + " as their parent" );

                final Iterator j =
                        PomHelper.getChildModels( reactor, sourceGroupId, sourceArtifactId ).entrySet().iterator();

                while ( j.hasNext() )
                {
                    final Map.Entry target = (Map.Entry) j.next();
                    final String targetPath = (String) target.getKey();
                    final Model targetModel = (Model) target.getValue();
                    final Parent parent = targetModel.getParent();
                    getLog().debug( "Module: " + targetPath );
                    if ( sourceVersion.equals( parent.getVersion() ) )
                    {
                        getLog().debug(
                                "    parent already is " + ArtifactUtils.versionlessKey( sourceGroupId, sourceArtifactId )
                                        + ":" + sourceVersion );
                    }
                    else
                    {
                        getLog().debug(
                                "    parent is " + ArtifactUtils.versionlessKey( sourceGroupId, sourceArtifactId ) + ":"
                                        + parent.getVersion() );
                        getLog().debug(
                                "    will become " + ArtifactUtils.versionlessKey( sourceGroupId, sourceArtifactId ) + ":"
                                        + sourceVersion );
                    }
                    final boolean targetExplicit = PomHelper.isExplicitVersion( targetModel );
                    if ( ( updateMatchingVersions.booleanValue() || !targetExplicit ) && StringUtils.equals(
                            parent.getVersion(), PomHelper.getVersion( targetModel ) ) )
                    {
                        getLog().debug(
                                "    module is " + ArtifactUtils.versionlessKey( PomHelper.getGroupId( targetModel ),
                                        PomHelper.getArtifactId( targetModel ) )
                                        + ":" + PomHelper.getVersion( targetModel ) );
                        getLog().debug(
                                "    will become " + ArtifactUtils.versionlessKey( PomHelper.getGroupId( targetModel ),
                                        PomHelper.getArtifactId( targetModel ) )
                                        + ":" + sourceVersion );
                        addChange( PomHelper.getGroupId( targetModel ), PomHelper.getArtifactId( targetModel ),
                                PomHelper.getVersion( targetModel ), sourceVersion );
                        targetModel.setVersion( sourceVersion );
                    }
                    else
                    {
                        getLog().debug(
                                "    module is " + ArtifactUtils.versionlessKey( PomHelper.getGroupId( targetModel ),
                                        PomHelper.getArtifactId( targetModel ) )
                                        + ":" + PomHelper.getVersion( targetModel ) );
                    }
                }
            }

            // now process all the updates
            final Iterator k = files.iterator();
            while ( k.hasNext() )
            {
                process( (File) k.next() );
            }

        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }
    }

    /**
     * Updates the pom file.
     *
     * @param pom The pom file to update.
     * @throws org.apache.maven.plugin.MojoExecutionException
     *          when things go wrong.
     * @throws org.apache.maven.plugin.MojoFailureException
     *          when things go wrong.
     * @throws javax.xml.stream.XMLStreamException
     *          when things go wrong.
     */
    protected synchronized void update( ModifiedPomXMLEventReader pom )
            throws MojoExecutionException, MojoFailureException, XMLStreamException
    {
        ContextualLog log = new DelegatingContextualLog( getLog() );
        try
        {
            Model model = PomHelper.getRawModel( pom );
            log.setContext( "Processing " + PomHelper.getGroupId( model ) + ":" + PomHelper.getArtifactId( model ) );

            VersionChangerFactory versionChangerFactory = new VersionChangerFactory();
            versionChangerFactory.setPom( pom );
            versionChangerFactory.setLog( log );
            versionChangerFactory.setModel( model );

            VersionChanger changer =
                    versionChangerFactory.newVersionChanger( processParent, processProject, processDependencies,
                            processPlugins );

            Iterator i = sourceChanges.iterator();
            while ( i.hasNext() )
            {
                VersionChange versionChange = (VersionChange) i.next();
                changer.apply( versionChange );
            }
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }
        log.clearContext();
    }

    /**
     * The version of the dependency/module to update.
     *
     * @parameter expression="${oldVersion}" default-value="${project.version}"
     * @since 1.2
     */
    public void setOldVersion(String oldVersion) {
        VersionStore.setVersionIfEmpty(oldVersion);
    }

    public String getOldVersion() {
        return VersionStore.getVersion();
    }
}
