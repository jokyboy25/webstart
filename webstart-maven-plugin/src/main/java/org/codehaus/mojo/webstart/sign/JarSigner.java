package org.codehaus.mojo.webstart.sign;

import java.io.File;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

/**
 * Created by timw on 14/03/15.
 */
public interface JarSigner
{
    void execute( File[] jarFiles, SignConfig signConfig, Log logger, boolean signVerify )
        throws MojoExecutionException;
}