package org.codehaus.mojo.jasperreports;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperReportsContext;
import net.sf.jasperreports.engine.design.JRAbstractMultiClassCompiler;

import org.apache.maven.plugin.logging.Log;
import org.apache.maven.toolchain.Toolchain;
import org.codehaus.plexus.compiler.Compiler;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerError;
import org.codehaus.plexus.compiler.CompilerException;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.StringUtils;


/**
 * @author Julien HENRY (henryju@users.sourceforge.net)
 */
public class MavenJavacCompiler extends JRAbstractMultiClassCompiler
{

    private static Log log;
    
    private static String compilerId = "javac";
    
    private static Compiler compiler;
    
    private static Toolchain tc;
    
    private static boolean debug;
    
    private static String encoding;
    
    private static String sourceVersion;
    
    private static String targetVersion;
    
    private boolean fork = false;
    
    private String executable = null;
    
    public static Log getLog() {
        return log;
    }
    
    public static void init(Log log, Compiler compiler, boolean debug, String encoding, Toolchain tc, String sourceVersion, String targetVersion) {
        MavenJavacCompiler.log = log;
        MavenJavacCompiler.compiler = compiler;
        MavenJavacCompiler.debug = debug;
        MavenJavacCompiler.encoding = encoding;
        MavenJavacCompiler.tc = tc;
        MavenJavacCompiler.sourceVersion = sourceVersion;
        MavenJavacCompiler.targetVersion = targetVersion;
    }

    /**
     * NMS-5182: The JasperReports API changed. It requires a constructor with a JasperReportContext.
     * This was added to use reports compiled by {@link org.codehaus.mojo.jasperreports.MavenJavacCompiler}
     * during runtime with JasperReports Library.
     * @param jasperReportsContext The context to be used
     */
    public MavenJavacCompiler(JasperReportsContext jasperReportsContext)
    {
        super(jasperReportsContext);
    }

    /**
     * This is due to backwards compatibility reasons.
     *
     * @deprecated Replaced by {@link #MavenJavacCompiler(JasperReportsContext)}.
     */
    public MavenJavacCompiler()
    {
        this(DefaultJasperReportsContext.getInstance());
    }


    @Override
	public String compileClasses(File[] sourceFiles, String classpath) throws JRException
	{
	 // ----------------------------------------------------------------------
        // Look up the compiler. This is done before other code than can
        // cause the mojo to return before the lookup is done possibly resulting
        // in misconfigured POMs still building.
        // ----------------------------------------------------------------------

        //-----------toolchains start here ----------------------------------
        //use the compilerId as identifier for toolchains as well.
        Toolchain tc = getToolchain();
        if ( tc != null ) 
        {
            getLog().info( "Toolchain in compiler-plugin: " + tc );
            if ( executable  != null ) 
            { 
                getLog().warn( "Toolchains are ignored, 'executable' parameter is set to " + executable );
            } 
            else 
            {
                fork = true;
                //TODO somehow shaky dependency between compilerId and tool executable.
                executable = tc.findTool( compilerId );
            }
        }
        // ----------------------------------------------------------------------
        //
        // ----------------------------------------------------------------------

        List<String> classpathAsList = new ArrayList<String>(Arrays.asList(classpath.split(",")));
        Set<File> sourceFilesAsSet = new HashSet<File>(Arrays.asList(sourceFiles));
        
        if ( getLog().isDebugEnabled() )
        {
            getLog().debug( "Classpath: " + classpath.replace( ',', '\n' ) );
        }

        // ----------------------------------------------------------------------
        // Create the compiler configuration
        // ----------------------------------------------------------------------

        CompilerConfiguration compilerConfiguration = new CompilerConfiguration();

        compilerConfiguration.setClasspathEntries( classpathAsList );
        
        compilerConfiguration.setOutputLocation( sourceFiles[0].getParent() );
        
        compilerConfiguration.setWorkingDirectory( sourceFiles[0].getParentFile() );

        compilerConfiguration.setSourceFiles(sourceFilesAsSet);

        compilerConfiguration.setDebug( debug );

        compilerConfiguration.setSourceEncoding( encoding );
        
        compilerConfiguration.setSourceVersion( sourceVersion );
        
        compilerConfiguration.setTargetVersion( targetVersion );
        
        compilerConfiguration.setFork( fork );

        compilerConfiguration.setExecutable( executable );


        // ----------------------------------------------------------------------
        // Dump configuration
        // ----------------------------------------------------------------------

        if ( getLog().isDebugEnabled() )
        {
            try
            {
                if ( fork )
                {
                    if ( compilerConfiguration.getExecutable() != null )
                    {
                        getLog().debug( "Excutable: " );
                        getLog().debug( " " + compilerConfiguration.getExecutable() );
                    }
                }

                String[] cl = compiler.createCommandLine( compilerConfiguration );
                if ( cl != null && cl.length > 0 )
                {
                    StringBuffer sb = new StringBuffer();
                    sb.append( cl[0] );
                    for ( int i = 1; i < cl.length; i++ )
                    {
                        sb.append( " " );
                        sb.append( cl[i] );
                    }
                    getLog().debug( "Command line options:" );
                    getLog().debug( sb );
                }
            }
            catch ( CompilerException ce )
            {
                getLog().debug( ce );
            }
        }

        // ----------------------------------------------------------------------
        // Compile!
        // ----------------------------------------------------------------------

        if ( StringUtils.isEmpty( compilerConfiguration.getSourceEncoding() ) )
        {
            getLog().warn(
                           "File encoding has not been set, using platform encoding " + ReaderFactory.FILE_ENCODING
                               + ", i.e. build is platform dependent!" );
        }

        List<CompilerError> messages;

        try
        {
            messages = compiler.compile( compilerConfiguration );
        }
        catch ( Exception e )
        {
            // TODO: don't catch Exception
            throw new JRException( "Fatal error compiling", e );
        }

        List<CompilerError> warnings = new ArrayList<CompilerError>();
        List<CompilerError> errors = new ArrayList<CompilerError>();
        if ( messages != null )
        {
            for ( CompilerError message : messages )
            {
                if ( message.isError() )
                {
                    errors.add( message );
                }
                else
                {
                    warnings.add( message );
                }
            }
        }

        if ( !errors.isEmpty() )
        {
            if ( !warnings.isEmpty() )
            {
                getLog().info( "-------------------------------------------------------------" );
                getLog().warn( "COMPILATION WARNING : " );
                getLog().info( "-------------------------------------------------------------" );
                for ( CompilerError warning : warnings )
                {
                    getLog().warn( warning.toString() );
                }
                getLog().info( warnings.size() + ( ( warnings.size() > 1 ) ? " warnings " : "warning" ) );
                getLog().info( "-------------------------------------------------------------" );
            }
            
            getLog().info( "-------------------------------------------------------------" );
            getLog().error( "COMPILATION ERROR : " );
            getLog().info( "-------------------------------------------------------------" );
            
            for ( CompilerError error : errors )
            {
                    getLog().error( error.toString() );
            }
            getLog().info( errors.size() + ( ( errors.size() > 1 ) ? " errors " : "error" ) );
            getLog().info( "-------------------------------------------------------------" );
            
            throw new JRException( "Error compiling report" );
        }
        else
        {
            for ( CompilerError message : messages )
            {
                getLog().warn( message.toString() );
            }
        }
        
        return null;
    }

    private static Toolchain getToolchain()
    {
        return tc;
    }
}
