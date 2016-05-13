package org.codehaus.mojo.webstart;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with work for additional information
 * regarding copyright ownership.  The ASF licenses file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use file except in compliance
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

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.AndArtifactFilter;
import org.apache.maven.artifact.resolver.filter.InversionArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.artifact.resolver.filter.TypeArtifactFilter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.filter.AncestorOrSelfDependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.filter.ArtifactDependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.filter.DependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.traversal.BuildingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.CollectingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.FilteringDependencyNodeVisitor;
import org.codehaus.mojo.webstart.generator.GeneratorTechnicalConfig;
import org.codehaus.mojo.webstart.generator.JarResourceGeneratorConfig;
import org.codehaus.mojo.webstart.generator.JarResourcesGenerator;
import org.codehaus.mojo.webstart.generator.VersionXmlGenerator;
import org.codehaus.mojo.webstart.util.ArtifactUtil;
import org.codehaus.mojo.webstart.util.IOUtil;

/**
 * MOJO is tailored for use within a Maven web application project that uses
 * the JnlpDownloadServlet to serve up the JNLP application.
 *
 * @author Kevin Stembridge
 * @version $Id$
 * @since 1.0-alpha-2
 */
@Mojo( name = "jnlp-download-servlet", requiresProject = true, inheritByDefault = true,
       requiresDependencyResolution = ResolutionScope.RUNTIME )
public class JnlpDownloadServletMojo
    extends AbstractBaseJnlpMojo
{

    // ----------------------------------------------------------------------
    // Constants
    // ----------------------------------------------------------------------

    /**
     * Name of the built-in servlet template to use if none is given.
     */
    private static final String BUILT_IN_SERVLET_TEMPLATE_FILENAME = "default-jnlp-servlet-template.vm";

    /**
     * Name of the default jnlp extension template to use if user define it in the default template directory.
     */
    private static final String SERVLET_TEMPLATE_FILENAME = "servlet-template.vm";
    
    private static final String JNLP_FILE_NAME_IN_JAR = "JNLP-INF/APPLICATION_TEMPLATE.JNLP";

    // ----------------------------------------------------------------------
    // Mojo Parameters
    // ----------------------------------------------------------------------

    /**
     * The name of the directory into which the jnlp file and other
     * artifacts will be stored after processing. directory will be created
     * directly within the root of the WAR produced by the enclosing project.
     */
    @Parameter( property = "jnlp.outputDirectoryName", defaultValue = "webstart" )
    private String outputDirectoryName;

    /**
     * The collection of JnlpFile configuration elements. Each one represents a
     * JNLP file that is to be generated and deployed within the enclosing
     * project's WAR artifact. At least one JnlpFile must be specified.
     */
    @Parameter( required = true )
    private List<JnlpFile> jnlpFiles;

    /**
     * The configurable collection of jars that are common to all jnlpFile elements declared in
     * plugin configuration. These jars will be output as jar elements in the resources section of
     * every generated JNLP file and bundled into the specified output directory of the artifact
     * produced by the project.
     */
    @Parameter
    private List<JarResource> commonJarResources;

    /**
     */
    @Parameter( defaultValue = "${reactorProjects}", required = true, readonly = true )
    private List<MavenProject> reactorProjects;

    // ----------------------------------------------------------------------
    // Components
    // ----------------------------------------------------------------------

    /**
     * Maven project.
     */
    @Component
    private MavenProject project;
    
    @Component
    protected MavenProjectBuilder mavenProjectBuilder;
    
    @Component
    private DependencyGraphBuilder dependencyGraphBuilder;

    // ----------------------------------------------------------------------
    // Mojo Implementation
    // ----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {

        // ---
        // Check configuration and get all configured jar resources
        // ---

        checkConfiguration();

        // ---
        // Prepare working directory layout
        // ---

        IOUtil ioUtil = getIoUtil();

        ioUtil.makeDirectoryIfNecessary( getWorkDirectory() );
        ioUtil.copyResources( getResourcesDirectory(), getWorkDirectory() );

        // ---
        // Resolve common jar resources
        // ---

        getLog().info( "-- Prepare commons jar resources" );
        Set<ResolvedJarResource> resolvedCommonJarResources;

        if ( CollectionUtils.isEmpty( commonJarResources ) )
        {
            resolvedCommonJarResources = Collections.emptySet();
        }
        else
        {
            resolvedCommonJarResources = resolveJarResources( commonJarResources, null, "" );
        }

        Set<ResolvedJarResource> allResolvedJarResources = new LinkedHashSet<ResolvedJarResource>();
        allResolvedJarResources.addAll( resolvedCommonJarResources );

        // ---
        // Resolved jnlpFiles
        // ---

        getLog().info( "-- Prepare jnlp files" );

        for ( JnlpFile jnlpFile : jnlpFiles )
        {
            verboseLog( "prepare jnlp " + jnlpFile );

            // resolve jar resources of the jnpl file
            Set<ResolvedJarResource> resolvedJarResources =
                resolveJarResources( jnlpFile.getJarResources(), resolvedCommonJarResources , jnlpFile.getMainClass());

            // keep them (to generate the versions.xml file)
            allResolvedJarResources.addAll( resolvedJarResources );

            // create the resolved jnlp file
            ResolvedJnlpFile resolvedJnlpFile = new ResolvedJnlpFile( jnlpFile, resolvedJarResources );
File jnlpGeneratedFile = generateJnlpFile( resolvedJnlpFile, getLibPath(), false);
            
            if(jnlpFile.isSignJnlp()){
            	Artifact artifactWithMainClass = null;
            	for(ResolvedJarResource resolvedJarResource: resolvedJarResources){
            		if(resolvedJarResource.getMainClass()  != null){
            			artifactWithMainClass = resolvedJarResource.getArtifact();
            			getLog().info("Find mainArtifactJar:" + artifactWithMainClass.getId());
            			break;
            		}
            	}
            	if(artifactWithMainClass != null){
	            	File[] libFiles = getLibDirectory().listFiles();
	            	File mainJarFile = null;
	            	for(int i=0; i<libFiles.length; i++){
	            		File lib = libFiles[i];
	            		if(lib.getName().equals("unprocessed_"+ artifactWithMainClass.getFile().getName())){
	            			mainJarFile = lib;
	            			getLog().info("Find mainJarFile in " + lib.getAbsoluteFile());
	            			break;
	            		}
	            	}
	            	if(mainJarFile != null){
	            		try{
	            			File jnlpSigningFile = generateJnlpFile( resolvedJnlpFile, getLibPath(), true );
	            			addJNLPinMainJar(mainJarFile, jnlpSigningFile);
	            			jnlpSigningFile.delete();
	            		}catch(IOException exception){
	            			throw new MojoExecutionException( "Failure to add JNLP file in Main artifact: ", exception );
	            		}
	            	}
            	}else{
            		throw new MojoExecutionException( "The main artifact containing MainClass was unavailable");
            	}
            	
            }
            
          
        }

        // ---
        // Process collected jars
        // ---

        signOrRenameJars();

        // ---
        // Generate version xml file
        // ---

        generateVersionXml( allResolvedJarResources );

        // ---
        // Copy to final directory
        // ---

        //FIXME Should be able to configure this
        File outputDir = new File( getProject().getBuild().getDirectory(),
                                   getProject().getBuild().getFinalName() + File.separator + outputDirectoryName );

        ioUtil.copyDirectoryStructure( getWorkDirectory(), outputDir );
    }

    // ----------------------------------------------------------------------
    // AbstractBaseJnlpMojo implementation
    // ----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    @Override
	public MavenProject getProject()
    {
        return project;
    }

    // ----------------------------------------------------------------------
    // Private methods
    // ----------------------------------------------------------------------

    /**
     * Confirms that all plugin configuration provided by the user
     * in the pom.xml file is valid.
     *
     * @throws MojoExecutionException if any user configuration is invalid.
     */
    private void checkConfiguration()
        throws MojoExecutionException
    {
        checkDependencyFilenameStrategy();

        if ( CollectionUtils.isEmpty( jnlpFiles ) )
        {
            throw new MojoExecutionException(
                "Configuration error: At least one <jnlpFile> element must be specified" );
        }

        if ( jnlpFiles.size() == 1 && StringUtils.isEmpty( jnlpFiles.get( 0 ).getOutputFilename() ) )
        {
            getLog().debug( "Jnlp output file name not specified in single set of jnlpFiles. " +
                                "Using default output file name: launch.jnlp." );
            jnlpFiles.get( 0 ).setOutputFilename( "launch.jnlp" );
        }

        // ---
        // check Jnlp files configuration
        // ---

        Set<String> filenames = new LinkedHashSet<String>( jnlpFiles.size() );

        for ( JnlpFile jnlpFile : jnlpFiles )
        {
            if ( !filenames.add( jnlpFile.getOutputFilename() ) )
            {
                throw new MojoExecutionException( "Configuration error: Unique JNLP filenames must be provided. " +
                                                      "The following file name appears more than once [" +
                                                      jnlpFile.getOutputFilename() + "]." );
            }

            checkJnlpFileConfiguration( jnlpFile );
        }

        if ( CollectionUtils.isNotEmpty( commonJarResources ) )
        {

            // ---
            // --- checkCommonJarResources();
            // ---

            for ( JarResource jarResource : commonJarResources )
            {
                checkMandatoryJarResourceFields( jarResource );

                if ( jarResource.getMainClass() != null )
                {
                    throw new MojoExecutionException( "Configuration Error: A mainClass must not be specified " +
                                                          "on a JarResource in the commonJarResources collection." );
                }
            }

            // ---
            // check for duplicate jar resources
            // Checks that any jarResources defined in the jnlpFile elements are not also defined in
            // commonJarResources.
            // ---

            for ( JnlpFile jnlpFile : jnlpFiles )
            {
                for ( JarResource jarResource : jnlpFile.getJarResources() )
                {
                    if ( commonJarResources.contains( jarResource ) )
                    {
                        String message = "Configuration Error: The jar resource element for artifact " + jarResource +
                            " defined in common jar resources is duplicated in the jar " +
                            "resources configuration of the jnlp file identified by the template file " +
                            jnlpFile.getInputTemplate() + ".";

                        throw new MojoExecutionException( message );
                    }
                }
            }
        }
    }

    /**
     * Checks the validity of a single jnlpFile configuration element.
     *
     * @param jnlpFile The configuration element to be checked.
     * @throws MojoExecutionException if the config element is invalid.
     */
    private void checkJnlpFileConfiguration( JnlpFile jnlpFile )
        throws MojoExecutionException
    {

        if ( StringUtils.isBlank( jnlpFile.getOutputFilename() ) )
        {
            throw new MojoExecutionException(
                "Configuration error: An outputFilename must be specified for each jnlpFile element" );
        }

        if ( StringUtils.isNotBlank( jnlpFile.getTemplateFilename() ) )
        {
            getLog().warn(
                "jnlpFile.templateFilename is deprecated (since 1.0-beta-5), use now the jnlpFile.inputTemplate instead." );
            jnlpFile.setInputTemplate( jnlpFile.getTemplateFilename() );
        }
//        if ( StringUtils.isBlank( jnlpFile.getInputTemplate() ) )
//        {
//            verboseLog(
//                "No templateFilename found for " + jnlpFile.getOutputFilename() + ". Will use the default template." );
//        }
//        else
//        {
//            File templateFile = new File( getTemplateDirectory(), jnlpFile.getInputTemplate() );
//
//            if ( !templateFile.isFile() )
//            {
//                throw new MojoExecutionException(
//                    "The specified JNLP template does not exist: [" + templateFile + "]" );
//            }
//        }

        List<JarResource> jnlpJarResources = jnlpFile.getJarResources();

        if ( CollectionUtils.isEmpty( jnlpJarResources ) )
        {
            throw new MojoExecutionException(
                "Configuration error: A non-empty <jarResources> element must be specified in the plugin " +
                    "configuration for the JNLP file named [" + jnlpFile.getOutputFilename() + "]" );
        }

        // ---
        // find out the jar resource with a main class (can only get one)
        // ---

        JarResource mainJarResource = null;

        for ( JarResource jarResource : jnlpJarResources )
        {
            checkMandatoryJarResourceFields( jarResource );

            if ( jarResource.getMainClass() != null )
            {
                if ( mainJarResource != null )
                {

                    // alreay found
                    throw new MojoExecutionException(
                        "Configuration error: More than one <jarResource> element has been declared " +
                            "with a <mainClass> element in the configuration for JNLP file [" +
                            jnlpFile.getOutputFilename() +
                            "]" );
                }

                jnlpFile.setMainClass( jarResource.getMainClass() );
                mainJarResource = jarResource;
            }
        }

        if ( mainJarResource == null )
        {
            throw new MojoExecutionException( "Configuration error: Exactly one <jarResource> element must " +
                                                  "be declared with a <mainClass> element in the configuration for JNLP file [" +
                                                  jnlpFile.getOutputFilename() + "]" );
        }
    }

    /**
     * Checks mandatory files of the given jar resource (says groupId, artificatId or version).
     *
     * @param jarResource jar resource to check
     * @throws MojoExecutionException if one of the mandatory field is missing
     */
    private void checkMandatoryJarResourceFields( JarResource jarResource )
        throws MojoExecutionException
    {

        if ( !jarResource.isMandatoryField() )
        {
            throw new MojoExecutionException(
                "Configuration error: groupId, artifactId or version missing for jarResource[" + jarResource + "]." );
        }

    }

    /**
     * Resolve artifact of incoming jar resources (user configured ones), check their main class.
     * <p/>
     * If must include transitive dependencies, collect them and wrap them as new jar resources.
     * <p/>
     * For each collected jar resource, copy his artifact file to lib directory (if it has changed),
     * fill also his hrefValue if required (jar resource with outputJarVersion filled).
     *
     * @param configuredJarResources list of configured jar resources
     * @param commonJarResources     list of resolved common jar resources (null when resolving common jar resources)
     * @return the set of resolved jar resources
     * @throws MojoExecutionException if something bas occurs while retrieving resources
     */
    private Set<ResolvedJarResource> resolveJarResources( Collection<JarResource> configuredJarResources,
                                                          Set<ResolvedJarResource> commonJarResources, String mainClass )
        throws MojoExecutionException
    {


    	Set<ResolvedJarResource> collectedJarResources = new LinkedHashSet<ResolvedJarResource>();

        if ( commonJarResources != null )
        {
            collectedJarResources.addAll( commonJarResources );
        }

        ArtifactUtil artifactUtil = getArtifactUtil();

        // artifacts resolved from repositories
        Set<Artifact> artifactsPoms = new LinkedHashSet<Artifact>();

        // sibling projects hit from a jar resources (need a special transitive resolution)
        Set<MavenProject> siblingProjects = new LinkedHashSet<MavenProject>();

        // for each configured JarResource, create and resolve the corresponding artifact and
        // check it for the mainClass if specified
        for ( JarResource jarResource : configuredJarResources )
        {
            Artifact artifact = artifactUtil.createArtifact( jarResource );
            Artifact artifactPom = artifactUtil.createArtifactPom( jarResource );
            // first try to resolv from reactor
            MavenProject siblingProject = artifactUtil.resolveFromReactor( artifact, getProject(), reactorProjects );
            if ( siblingProject == null )
            {
                // try to resolve from repositories
                artifactUtil.resolveFromRepositories( artifact, getRemoteRepositories(), getLocalRepository() );
                artifactUtil.resolveFromRepositories( artifactPom, getRemoteRepositories(), getLocalRepository() );
                artifactsPoms.add( artifactPom );
            }
            else
            {
                artifact = siblingProject.getArtifact();
                siblingProjects.add( siblingProject );
                artifactUtil.resolveFromRepositories( artifactPom, getRemoteRepositories(), getLocalRepository() );
                artifactsPoms.add( artifactPom );
                artifact.setResolved( true );
            }

            if ( StringUtils.isNotBlank( jarResource.getMainClass() ) )
            {
                // check main class

                if ( artifact == null )
                {
                    throw new IllegalStateException(
                        "Implementation Error: The given jarResource cannot be checked for " +
                            "a main class until the underlying artifact has been resolved: [" +
                            jarResource + "]" );
                }

                boolean containsMainClass = artifactUtil.artifactContainsClass( artifact, jarResource.getMainClass() );
                if ( !containsMainClass )
                {
                    throw new MojoExecutionException(
                        "The jar specified by the following jarResource does not contain the declared main class:" +
                            jarResource );
                }
            }
            ResolvedJarResource resolvedJarResource = new ResolvedJarResource( jarResource, artifact );
            getLog().debug( "Add jarResource (configured): " + jarResource );
            collectedJarResources.add( resolvedJarResource );
        }

        if ( !isExcludeTransitive() )
        {
            
            try {
            	Set<Artifact> transitiveArtifacts = new HashSet<Artifact>();
            	for(Artifact artifactPom : artifactsPoms){
	            	// prepare artifact filter
	            	AndArtifactFilter artifactFilter = new AndArtifactFilter();
	            	// restricts to runtime and compile scope
	            	artifactFilter.add( new ScopeArtifactFilter( Artifact.SCOPE_RUNTIME ) );
	            	// restricts to not pom dependencies
	            	//artifactFilter.add( new InversionArtifactFilter( new TypeArtifactFilter( "pom" ) ) );
	
	            	// get all transitive dependencies
	
	            	final MavenProject pomProject =
	            			mavenProjectBuilder.buildFromRepository( artifactPom, getRemoteRepositories(),  getLocalRepository() );
	
	            	DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph(pomProject,artifactFilter);
	
	            	CollectingDependencyNodeVisitor collectingVisitor = new CollectingDependencyNodeVisitor();
	            	AndArtifactFilter artifactFilter2 = new AndArtifactFilter();
	            	artifactFilter2.add( new ScopeArtifactFilter( Artifact.SCOPE_RUNTIME ) );
	            	artifactFilter2.add( new InversionArtifactFilter( new TypeArtifactFilter( "pom" ) ) );
	            	DependencyNodeVisitor firstPassVisitor = new FilteringDependencyNodeVisitor( collectingVisitor, new ArtifactDependencyNodeFilter( artifactFilter2 ) );
	            	rootNode.accept( firstPassVisitor );
	
	            	CollectingDependencyNodeVisitor visitor2 = new CollectingDependencyNodeVisitor();
	            	DependencyNodeVisitor visitor = new BuildingDependencyNodeVisitor(visitor2);
	            	DependencyNodeFilter secondPassFilter = new AncestorOrSelfDependencyNodeFilter( collectingVisitor.getNodes() );
	            	visitor = new FilteringDependencyNodeVisitor( visitor, secondPassFilter );
	            	rootNode.accept(visitor);
		            	
	            	List<DependencyNode> nodes = collectingVisitor.getNodes();
	            	for (DependencyNode dependencyNode : nodes) {
	            		Artifact artifact = dependencyNode.getArtifact();
	            		artifactUtil.resolveFromRepositories( artifact, getRemoteRepositories(), getLocalRepository() );
	            		transitiveArtifacts.add(artifact);
	            	}
            	}

            	for ( Artifact resolvedArtifact : transitiveArtifacts )
            	{
            		ResolvedJarResource newJarResource = new ResolvedJarResource( resolvedArtifact );

            		if ( !collectedJarResources.contains( newJarResource ) )
            		{
            			getLog().debug( "Add jarResource (transitive): " + newJarResource );
            			collectedJarResources.add( newJarResource );
            		}
            	}
            } catch (ProjectBuildingException e) {
    			// TODO Auto-generated catch block
    			e.printStackTrace();
    		} catch (DependencyGraphBuilderException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }

        // for each JarResource, copy its artifact to the lib directory if necessary
        for ( ResolvedJarResource jarResource : collectedJarResources )
        {
            Artifact artifact = jarResource.getArtifact();

            String filenameWithVersion =
                getDependencyFilenameStrategy().getDependencyFilename( artifact, false, isUseUniqueVersions() );

            boolean copied = copyJarAsUnprocessedToDirectoryIfNecessary( artifact.getFile(), getLibDirectory(),
                                                                         filenameWithVersion );

            if ( copied )
            {
                String name = artifact.getFile().getName();

                verboseLog( "Adding " + name + " to modifiedJnlpArtifacts list." );
                getModifiedJnlpArtifacts().add( name.substring( 0, name.lastIndexOf( '.' ) ) );
            }

            String filename = getDependencyFilenameStrategy().getDependencyFilename( artifact,
                                                                                     jarResource.isOutputJarVersion()
                                                                                         ? null
                                                                                         : false,
                                                                                     isUseUniqueVersions() );
            jarResource.setHrefValue( filename );
        }
       

        return collectedJarResources;
    }

    private File generateJnlpFile( ResolvedJnlpFile jnlpFile, String libPath , boolean signing )
        throws MojoExecutionException
    {

    	String outputFileName = jnlpFile.getOutputFilename();
    	if(signing){
    		outputFileName += ".signing";
    	}

        File jnlpOutputFile = new File( getWorkDirectory(), outputFileName );

        Set<ResolvedJarResource> jarResources = jnlpFile.getJarResources();

        // ---
        // get template directory
        // ---

        File templateDirectory;

        if ( StringUtils.isNotBlank( jnlpFile.getInputTemplateResourcePath() ) )
        {
            templateDirectory = new File( jnlpFile.getInputTemplateResourcePath() );
            getLog().debug( "Use jnlp directory : " + templateDirectory );
        }
        else
        {
            // use default template directory
            templateDirectory = getTemplateDirectory();
            getLog().debug( "Use default template directory : " + templateDirectory );
        }

        // ---
        // get template filename
        // ---

        if ( StringUtils.isBlank( jnlpFile.getInputTemplate() ) )
        {
            getLog().debug(
                "Jnlp servlet template file name not specified. Checking if default output file name exists: " +
                    SERVLET_TEMPLATE_FILENAME );

            File templateFile = new File( templateDirectory, SERVLET_TEMPLATE_FILENAME );

            if ( templateFile.isFile() )
            {
                jnlpFile.setInputTemplate( SERVLET_TEMPLATE_FILENAME );
            }
            else
            {
                getLog().debug( "Jnlp servlet template file not found in default location. Using inbuilt one." );
            }
        }
        else
        {
            File templateFile = new File( templateDirectory, jnlpFile.getInputTemplate() );

            if ( !templateFile.isFile() )
            {
                throw new MojoExecutionException(
                    "The specified JNLP servlet template does not exist: [" + templateFile + "]" );
            }
        }

        String templateFileName = jnlpFile.getInputTemplate();

        GeneratorTechnicalConfig generatorTechnicalConfig =
            new GeneratorTechnicalConfig( getProject(), templateDirectory, BUILT_IN_SERVLET_TEMPLATE_FILENAME,
                                          jnlpOutputFile, templateFileName, jnlpFile.getMainClass(),
                                          getWebstartJarURLForVelocity(), getEncoding() );

        JarResourceGeneratorConfig jarResourceGeneratorConfig = new JarResourceGeneratorConfig( jarResources, libPath, getCodebase(), jnlpFile.getProperties(), jnlpFile.getArguments(), signing );

        JarResourcesGenerator jnlpGenerator =
            new JarResourcesGenerator( getLog(), generatorTechnicalConfig, jarResourceGeneratorConfig );

//        jnlpGenerator.setExtraConfig( new SimpleGeneratorExtraConfig( jnlpFile.getProperties(), getCodebase() ) );

        try
        {
            jnlpGenerator.generate();
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException(
                "The following error occurred attempting to generate " + "the JNLP deployment descriptor: " + e, e );
        }
        
        return jnlpOutputFile;

    }

    /**
     * Generates a version.xml file for all the jarResources configured either in jnlpFile elements
     * or in the commonJarResources element.
     *
     * @throws MojoExecutionException if could not generate the xml version file
     */
    private void generateVersionXml( Set<ResolvedJarResource> jarResources )
        throws MojoExecutionException
    {

        VersionXmlGenerator generator = new VersionXmlGenerator( getEncoding() );
        generator.generate( getLibDirectory(), jarResources );
    }

//    /**
//     * Builds the string to be entered in the href attribute of the jar
//     * resource element in the generated JNLP file. will be equal
//     * to the artifact file name with the version number stripped out.
//     *
//     * @param artifact The underlying artifact of the jar resource.
//     * @return The href string for the given artifact, never null.
//     */
//    private String buildHrefValue( Artifact artifact )
//    {
//        StringBuilder sbuf = new StringBuilder();
//        sbuf.append( artifact.getArtifactId() );
//
//        if ( StringUtils.isNotEmpty( artifact.getClassifier() ) )
//        {
//            sbuf.append( "-" ).append( artifact.getClassifier() );
//        }
//
//        sbuf.append( "." ).append( artifact.getArtifactHandler().getExtension() );
//
//        return sbuf.toString();
//    }
    
    public void addJNLPinMainJar(File mainJarFile, File jnlpFile)
			throws IOException {

		String mainJarFileName = mainJarFile.getAbsolutePath();
		File tempJarFile = new File(mainJarFileName + ".tmp");

		// Open the jar file.
		JarFile jar = new JarFile(mainJarFile);
		getLog().info(mainJarFileName + " opened.");

		// Initialize a flag that will indicate that the jar was updated.
		boolean jarUpdated = false;

		try {
			// Create a temp jar file with no manifest. (The manifest will
			// be copied when the entries are copied.)

			Manifest jarManifest = jar.getManifest();
			JarOutputStream tempJar = new JarOutputStream(new FileOutputStream(
					tempJarFile));

			// Allocate a buffer for reading entry data.

			byte[] buffer = new byte[1024];
			int bytesRead;

			try {
				// Open the given file.

				FileInputStream file = new FileInputStream(jnlpFile);

				try {
					// Create a jar entry and add it to the temp jar.
					JarEntry entry = new JarEntry(JNLP_FILE_NAME_IN_JAR);
					tempJar.putNextEntry(entry);

					// Read the file and write it to the jar.

					while ((bytesRead = file.read(buffer)) != -1) {
						tempJar.write(buffer, 0, bytesRead);
					}

					getLog().info(entry.getName() + " added.");
				} finally {
					file.close();
				}

				// Loop through the jar entries and add them to the temp jar,
				// skipping the entry that was added to the temp jar already.

				for (Enumeration entries = jar.entries(); entries
						.hasMoreElements();) {
					// Get the next entry.

					JarEntry entry = (JarEntry) entries.nextElement();

					// If the entry has not been added already, add it.

					if (!entry.getName().equals(JNLP_FILE_NAME_IN_JAR)) {
						// Get an input stream for the entry.

						InputStream entryStream = jar.getInputStream(entry);

						// Read the entry and write it to the temp jar.

						tempJar.putNextEntry(entry);

						while ((bytesRead = entryStream.read(buffer)) != -1) {
							tempJar.write(buffer, 0, bytesRead);
						}
					}
				}

				jarUpdated = true;
			} catch (Exception ex) {
				getLog().info(ex);

				// Add a stub entry here, so that the jar will close without an
				// exception.

				tempJar.putNextEntry(new JarEntry("stub"));
			} finally {
				tempJar.close();
			}
		} finally {
			jar.close();
			getLog().info(mainJarFileName + " closed.");

			// If the jar was not updated, delete the temp jar file.

			if (!jarUpdated) {
				tempJarFile.delete();
			}
		}

		// If the jar was updated, delete the original jar file and rename the
		// temp jar file to the original name.

		if (jarUpdated) {
			mainJarFile.delete();
			tempJarFile.renameTo(mainJarFile);
			getLog().info(mainJarFileName + " updated.");
		}
    }
}
