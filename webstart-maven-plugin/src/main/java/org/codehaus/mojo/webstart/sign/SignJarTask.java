package org.codehaus.mojo.webstart.sign;

import static org.codehaus.mojo.webstart.AbstractBaseJnlpMojo.UNPROCESSED_PREFIX;

import java.io.File;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.mojo.webstart.util.IOUtil;

public class SignJarTask implements Runnable
{
    private File jarFile;

    private IOUtil ioUtil;

    private SignTool signTool;

    private SignConfig signConfig;

    private Log logger;

    private boolean signVerify;

    public SignJarTask( File jarFile, IOUtil ioUtil, SignTool signTool, SignConfig signConfig, Log logger,
                        boolean signVerify )
    {
        this.jarFile = jarFile;
        this.ioUtil = ioUtil;
        this.signTool = signTool;
        this.signConfig = signConfig;
        this.logger = logger;
        this.signVerify = signVerify;
    }

    public void run()
    {
        File signedJar = toProcessFile( jarFile );
        try
        {
            logger.debug( "Signing jar with thread " + Thread.currentThread().getName() );
            ioUtil.deleteFile( signedJar );
            logger.info( "Sign " + signedJar.getName() );
            signTool.sign( signConfig, jarFile, signedJar );
            logger.debug( "lastModified signedJar:" + signedJar.lastModified() + " unprocessed signed Jar:" +
                              jarFile.lastModified() );

            if ( signVerify )
            {
                logger.info( "Verify signature of " + signedJar.getName() );
                signTool.verify( signConfig, signedJar, true );
            }

            // remove unprocessed files
            // TODO wouldn't have to do that if we copied the unprocessed jar files in a temporary area
            ioUtil.deleteFile( jarFile );
        }
        catch ( MojoExecutionException e )
        {
            throw new RuntimeException( e );
        }
    }

    private File toProcessFile( File source )
    {
        if ( !source.getName().startsWith( UNPROCESSED_PREFIX ) )
        {
            throw new IllegalStateException( source.getName() + " does not start with " + UNPROCESSED_PREFIX );
        }

        String targetFilename = source.getName().substring( UNPROCESSED_PREFIX.length() );
        return new File( source.getParentFile(), targetFilename );
    }
}