package org.codehaus.mojo.jasperreports;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License") you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.compiler.Compiler;
import org.codehaus.plexus.compiler.manager.CompilerManager;
import org.codehaus.plexus.compiler.manager.NoSuchCompilerException;
import org.codehaus.plexus.compiler.util.scan.InclusionScanException;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.StaleSourceScanner;
import org.codehaus.plexus.compiler.util.scan.mapping.SourceMapping;
import org.codehaus.plexus.compiler.util.scan.mapping.SuffixMapping;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReportsContext;
import net.sf.jasperreports.engine.SimpleJasperReportsContext;
import net.sf.jasperreports.engine.design.JRCompiler;

/**
 * Compiles JasperReports xml definition files.
 * <p>
 * Much of this was inspired by the JRAntCompileTask, while trying to make it slightly cleaner and
 * easier to use with Maven's mojo api.
 * </p>
 * 
 * @author gjoseph
 * @author Tom Schwenk
 * @goal compile-reports
 * @phase generate-sources
 * @requiresDependencyResolution compile
 */
public class JasperReportsMojo
    extends AbstractMojo
{
    /**
     * @parameter expression="${project}
     */
    private MavenProject project;
    
    /**
     * The current build session instance. This is used for
     * toolchain manager API calls.
     *
     * @parameter default-value="${session}"
     * @required
     * @readonly
     */
    private MavenSession session;

    /**
     * This is where the generated java sources are stored.
     * 
     * @parameter expression="${project.build.directory}/jasperreports/java"
     */
    private File javaDirectory;

    /**
     * This is where the .jasper files are written.
     * 
     * @parameter expression="${project.build.outputDirectory}"
     */
    private File outputDirectory;

    /**
     * This is where the xml report design files should be.
     * 
     * @parameter default-value="src/main/jasperreports"
     */
    private File sourceDirectory;

    /**
     * The extension of the source files to look for. Finds files with a .jrxml extension by
     * default.
     * 
     * @parameter default-value=".jrxml"
     */
    private String sourceFileExt;

    /**
     * The extension of the compiled report files. Creates files with a .jasper extension by
     * default.
     * 
     * @parameter default-value=".jasper"
     */
    private String outputFileExt;

    /**
     * Since the JasperReports compiler deletes the compiled classes, one might want to set this to
     * true, if they want to handle the generated java source in their application. Mind that this
     * will not work if the mojo is bound to the compile or any other later phase. (As one might
     * need to do if they use classes from their project in their report design)
     * 
     * @parameter default-value="false"
     * @deprecated There seems to be an issue with the compiler plugin so don't expect this to work
     *             yet - the dependencies will have disappeared.
     */
    private boolean keepJava;

    /**
     * Not used for now - just a TODO - the idea being that one might want to set this to false if
     * they want to handle the generated java source in their application.
     * 
     * @parameter default-value="true"
     * @deprecated Not implemented
     */
    @SuppressWarnings("unused")
    private boolean keepSerializedObject;

    /**
     * Wether the xml design files must be validated.
     * 
     * @parameter default-value="true"
     */
    private boolean xmlValidation;

    /**
     * Uses the Javac compiler by default. This is different from the original JasperReports ant
     * task, which uses the JDT compiler by default.
     * 
     * @parameter default-value="org.codehaus.mojo.jasperreports.MavenJavacCompiler"
     */
    private String compiler;

    /**
     * @parameter expression="${project.compileClasspathElements}"
     */
    private List<String> classpathElements;

    /**
     * Additional JRProperties
     * @parameter 
     * @since 1.0-beta-2
     */
    private Map<String,String> additionalProperties = new HashMap<>();

    /**
     * Any additional classpath entry you might want to add to the JasperReports compiler. Not
     * recommended for general use, plugin dependencies should be used instead.
     * 
     * @parameter
     */
    private String additionalClasspath;

    /**
     * Plexus compiler manager.
     *
     * @component
     */
    private CompilerManager compilerManager;
    
    /** @component */
    private ToolchainManager toolchainManager;
    
    /**
     * The -source argument for the Java compiler.
     *
     * @parameter expression="${maven.compiler.source}" default-value="1.5"
     */
    protected String source;

    /**
     * The -target argument for the Java compiler.
     *
     * @parameter expression="${maven.compiler.target}" default-value="1.5"
     */
    protected String target;

    /**
     * The -encoding argument for the Java compiler.
     *
     * @parameter expression="${encoding}" default-value="${project.build.sourceEncoding}"
     */
    private String encoding;

    /**
     * Set to true to include debugging information in the compiled class files.
     *
     * @parameter expression="${maven.compiler.debug}" default-value="true"
     */
    private boolean debug = true;

    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        getLog().debug( "javaDir = " + javaDirectory );
        getLog().debug( "sourceDirectory = " + sourceDirectory );
        getLog().debug( "sourceFileExt = " + sourceFileExt );
        getLog().debug( "targetDirectory = " + outputDirectory );
        getLog().debug( "targetFileExt = " + outputFileExt );
        getLog().debug( "keepJava = " + keepJava );
        //getLog().debug("keepSerializedObject = " + keepSerializedObject);
        getLog().debug( "xmlValidation = " + xmlValidation );
        getLog().debug( "compiler = " + compiler );
        getLog().debug( "classpathElements = " + classpathElements );
        getLog().debug( "additionalClasspath = " + additionalClasspath );

        checkDir( javaDirectory, "Directory for generated java sources", true );
        checkDir( sourceDirectory, "Source directory", false );
        checkDir( outputDirectory, "Target directory", true );

        SourceMapping mapping = new SuffixMapping( sourceFileExt, outputFileExt );

        Set<File> staleSources = scanSrcDir( mapping );
        if ( staleSources.isEmpty() )
        {
            getLog().info( "Nothing to compile - all Jasper reports are up to date" );
        }
        else
        {
            // actual compilation
            compile( staleSources, mapping );

            if ( keepJava )
            {
                project.addCompileSourceRoot( javaDirectory.getAbsolutePath() );
            }
        }
    }

    protected void compile( Set<File> files, SourceMapping mapping )
        throws MojoFailureException, MojoExecutionException
    {
        String classpath = buildClasspathString( classpathElements, additionalClasspath );
        getLog().debug( "buildClasspathString() = " + classpath );

        getLog().info( "Compiling " + files.size() + " report design files." );

        getLog().debug( "Set classloader" );
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader( getClassLoader( classLoader ) );

        try
        {
            JasperReportsContext reportsContext = new SimpleJasperReportsContext();
            reportsContext.setProperty( JRCompiler.COMPILER_CLASSPATH, classpath );
            reportsContext.setProperty( JRCompiler.COMPILER_TEMP_DIR, javaDirectory.getAbsolutePath() );
            reportsContext.setProperty( JRCompiler.COMPILER_KEEP_JAVA_FILE, Boolean.toString(keepJava) );
            reportsContext.setProperty( JRCompiler.COMPILER_PREFIX, compiler );
            JasperCompileManager jasperCompilerManager = JasperCompileManager.getInstance(reportsContext);

            Compiler compilerMaven;
            
            String compilerId = "javac";

            getLog().debug( "Using compiler '" + compilerId + "'." );

            try
            {
                compilerMaven = compilerManager.getCompiler( compilerId );
            }
            catch ( NoSuchCompilerException e )
            {
                throw new MojoExecutionException( "No such compiler '" + e.getCompilerId() + "'." );
            }
            
            MavenJavacCompiler.init(getLog(), compilerMaven, debug, encoding, getToolchain(), source, target);

            for ( Iterator<String> i = additionalProperties.keySet().iterator(); i.hasNext(); )
            {
                String key = i.next();
                String value = additionalProperties.get( key );
                //JRProperties.setProperty( key, value );
                getLog().debug( "Added property: " + key + ":" + value );
            }

            Iterator<File> it = files.iterator();
            while ( it.hasNext() )
            {
                File src = (File) it.next();
                String srcName = getPathRelativeToRoot( src );
                try
                {
                    // get the single destination file
                    File dest = (File) mapping.getTargetFiles( outputDirectory, srcName ).iterator().next();

                    File destFileParent = dest.getParentFile();
                    if ( !destFileParent.exists() )
                    {
                        if ( destFileParent.mkdirs() )
                        {
                            getLog().debug( "Created directory " + destFileParent );
                        }
                        else
                        {
                            throw new MojoExecutionException( "Could not create directory " + destFileParent );
                        }
                    }
                    
                    final File targetdir = new File(project.getBasedir(), "target");
                    final File md5dir = new File(targetdir, "jaspermd5");

                    final File md5File = new File(md5dir, srcName + ".md5");
                    if (dest.exists() && md5File.exists()) {
                    	getLog().debug("destination exists, md5 file exists");
                    	try {
                            final String srcMd5String = getFileMd5(src);

					        try(final BufferedReader br = new BufferedReader(new FileReader(md5File))) {
	                            final String fileMd5 = br.readLine();

	                            if (srcMd5String.equals(fileMd5)) {
	                                getLog().info("Skipping report file: " + src + " (MD5 matches)");
	                                continue;
	                            }
					        }
						} catch (final Exception e) {
							getLog().warn("unable to read from " + src, e);
						}
                    }
                    getLog().info( "Compiling report file: " + srcName );
                    jasperCompilerManager.compileToFile( src.getAbsolutePath(), dest.getAbsolutePath() );
                    
                    try {
						final String destMd5 = getFileMd5(src);
						md5File.getParentFile().mkdirs();
						final FileWriter fr = new FileWriter(md5File);
						fr.write(destMd5);
						fr.write("\n");
						fr.close();
					} catch (final Exception e) {
						getLog().warn("unable to MD5 " + dest + ": " + e.getLocalizedMessage());
					}
                }
                catch ( JRException e )
                {
                    throw new MojoExecutionException( "Error compiling report design : " + src, e );
                }
                catch ( InclusionScanException e )
                {
                    throw new MojoExecutionException( "Error compiling report design : " + src, e );
                }
            }
        }
        finally
        {
            if ( classLoader != null ) {
                Thread.currentThread().setContextClassLoader( classLoader );
            }
        }
        getLog().info( "Compiled " + files.size() + " report design files." );
    }

	private String getFileMd5(File src) throws NoSuchAlgorithmException,
			FileNotFoundException, IOException {
		final MessageDigest md = MessageDigest.getInstance("MD5");
		try(final FileInputStream sourceIs = new FileInputStream(src)) {
	        byte[] dataBytes = new byte[1024];
	        int nread = 0;
	        while ((nread = sourceIs.read(dataBytes)) != -1) {
	          md.update(dataBytes, 0, nread);
	        };
		}
		final byte[] mdbytes = md.digest();
		final StringBuffer srcMd5 = new StringBuffer();
		for (int i = 0; i < mdbytes.length; i++) {
		  srcMd5.append(Integer.toString((mdbytes[i] & 0xff) + 0x100, 16).substring(1));
		}
		final String srcMd5String = srcMd5.toString();
		return srcMd5String;
	}

    /**
     * Determines source files to be compiled, based on the SourceMapping. No longer needs to be
     * recursive, since the SourceInclusionScanner handles that.
     *
     * @param mapping
     * @return
     * @throws org.apache.maven.plugin.MojoExecutionException
     */
    protected Set<File> scanSrcDir( SourceMapping mapping )
        throws MojoExecutionException
    {
        final int staleMillis = 0;

        SourceInclusionScanner scanner = new StaleSourceScanner( staleMillis );
        scanner.addSourceMapping( mapping );

        try
        {
            return scanner.getIncludedSources( sourceDirectory, outputDirectory );
        }
        catch ( InclusionScanException e )
        {
            throw new MojoExecutionException( "Error scanning source root: \'" + sourceDirectory + "\' "
                + "for stale files to recompile.", e );
        }
    }

    private String getPathRelativeToRoot( File file )
        throws MojoExecutionException
    {
        try
        {
            String root = this.sourceDirectory.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            if ( !filePath.startsWith( root ) )
            {
                throw new MojoExecutionException( "File is not in source root ??? " + file );
            }
            return filePath.substring( root.length() + 1 );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Could not getCanonicalPath from file " + file, e );
        }
    }

    protected String buildClasspathString( List<String> classpathElements, String additionalClasspath )
    {
        StringBuffer classpath = new StringBuffer();
        Iterator<String> it = classpathElements.iterator();
        while ( it.hasNext() )
        {
            String cpElement = it.next();
            classpath.append( cpElement );
            if ( it.hasNext() )
            {
                classpath.append( File.pathSeparator );
            }
        }
        if ( additionalClasspath != null )
        {
            if ( classpath.length() > 0 )
            {
                classpath.append( File.pathSeparator );
            }
            classpath.append( additionalClasspath );

        }
        return classpath.toString();
    }

    private void checkDir( File dir, String desc, boolean isTarget )
        throws MojoExecutionException
    {
        if ( dir.exists() && !dir.isDirectory() )
        {
            throw new MojoExecutionException( desc + " is not a directory : " + dir );
        }
        else if ( !dir.exists() && isTarget && !dir.mkdirs() )
        {
            throw new MojoExecutionException( desc + " could not be created : " + dir );
        }

        if ( isTarget && !dir.canWrite() )
        {
            throw new MojoExecutionException( desc + " is not writable : " + dir );
        }
    }

    private ClassLoader getClassLoader( ClassLoader classLoader )
        throws MojoExecutionException
    {
        List<URL> classpathURLs = new ArrayList<>();

        for ( int i = 0; i < classpathElements.size(); i++ )
        {
            String element = (String) classpathElements.get( i );
            try
            {
                File f = new File( element );
                URL newURL = f.toURI().toURL();
                classpathURLs.add( newURL );
                getLog().debug( "Added to classpath " + element );
            }
            catch ( Exception e )
            {
                throw new MojoExecutionException( "Error parsing classparh " + element + " " + e.getMessage() );
            }
        }

        if ( additionalClasspath != null && additionalClasspath.length() > 0 )
        {
            String[] elements = additionalClasspath.split( File.pathSeparator );
            for ( int i = 0; i < elements.length; i++ )
            {
                String element = elements[i];
                try
                {
                    File f = new File( element );
                    URL newURL = f.toURI().toURL();
                    classpathURLs.add( newURL );
                    getLog().debug( "Added to classpath " + element );
                }
                catch ( Exception e )
                {
                    throw new MojoExecutionException( "Error parsing classpath " + additionalClasspath + " "
                        + e.getMessage() );
                }
            }
        }

        URL[] urls = (URL[]) classpathURLs.toArray( new URL[classpathURLs.size()] );
        return new URLClassLoader( urls, classLoader );
    }
    
    //TODO remove the part with ToolchainManager lookup once we depend on
    //3.0.9 (have it as prerequisite). Define as regular component field then.
    private Toolchain getToolchain()
    {
        Toolchain tc = null;
        if ( toolchainManager != null )
        {
            tc = toolchainManager.getToolchainFromBuildContext( "jdk", session );
        }
        return tc;
    }

}
