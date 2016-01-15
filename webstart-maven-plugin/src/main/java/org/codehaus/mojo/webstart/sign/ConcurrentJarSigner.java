package org.codehaus.mojo.webstart.sign;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.mojo.webstart.util.IOUtil;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;

/**
 * Quick and dirty concurrent jar signing.
 * <p/>
 * TODO: Make sure jar signing tools are actually threadsafe (this class is experimental)
 * TODO: toProcessFile() is duplicated - reuse it somehow
 */
@Component( role = JarSigner.class, hint = "default" )
public class ConcurrentJarSigner
    implements JarSigner
{
    @Requirement
    private SignTool signTool;

    @Requirement
    private IOUtil ioUtil;

    ExecutorService executor;

    public void execute( File[] jarFiles, SignConfig signConfig, Log logger,
                         boolean signVerify )
        throws MojoExecutionException
    {
        int threads = signConfig.getThreads();
        this.executor = Executors.newFixedThreadPool( threads );

        logger.info( "Signing jars with " + threads + " threads" );
        long startTime = System.nanoTime();

        List<Future<?>> futures = signJars( jarFiles, ioUtil, signTool, signConfig, logger, signVerify );

        checkForFailures( futures );

        shutdown();

        logger.info( "Signing took " + getElapsedTime( startTime, System.nanoTime() ) + " seconds." );
    }

    private long getElapsedTime( long startTime, long endTime )
    {
        return ( endTime - startTime ) / 1000000000;
    }

    private List<Future<?>> signJars( File[] jarFiles, IOUtil ioUtil, SignTool signTool, SignConfig signConfig,
                                      Log logger, boolean signVerify )
    {
        List<Future<?>> futures = new ArrayList<Future<?>>();

        for ( File jarFile : jarFiles )
        {
            Runnable task = new SignJarTask( jarFile, ioUtil, signTool, signConfig, logger, signVerify );
            futures.add( executor.submit( task ) );
        }

        return futures;
    }

    private void shutdown()
        throws MojoExecutionException
    {
        this.executor.shutdown();

        try
        {
            // timeout value picked out of a hat
            this.executor.awaitTermination( 10, TimeUnit.MINUTES );
        }
        catch ( InterruptedException e )
        {
            throw new MojoExecutionException( "Error shutting down ConcurrentJarSigner", e );
        }
    }

    private void checkForFailures( List<Future<?>> futures )
        throws MojoExecutionException
    {
        for ( Future future : futures )
        {
            try
            {
                future.get();
            }
            catch ( Exception e )
            {
                throw new MojoExecutionException( "Error signing jar file", e );
            }
        }
    }
}