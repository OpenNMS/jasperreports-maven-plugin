package org.codehaus.mojo.jasperreports;

import junit.framework.TestCase;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.io.File;

/**
 * @author gjoseph
 * @author $Author: $ (last edit)
 * @version $Revision: $
 */
public class JasperReportsMojoTest
    extends TestCase
{
    public void testClasspathBuildingAddsStuffIfNeeded()
    {
        List fakeElements = Arrays.asList( new String[]{"foo", "bar", "baz"} );
        JasperReportsMojo mojo = new JasperReportsMojo();
        assertEquals( "foo" + File.pathSeparator + "bar" + File.pathSeparator + "baz",
                mojo.buildClasspathString( fakeElements, null ) );
        assertEquals( "foo" + File.pathSeparator + "bar" + File.pathSeparator + "baz" + File.pathSeparator + "bingo",
                mojo.buildClasspathString( fakeElements, "bingo" ) );
    }

    public void testClasspathBuildingWorksWithEmptyList()
    {
        JasperReportsMojo mojo = new JasperReportsMojo();
        assertEquals( "", mojo.buildClasspathString( Collections.EMPTY_LIST, null ) );
        assertEquals( "plop", mojo.buildClasspathString( Collections.EMPTY_LIST, "plop" ) );
    }

    public void testClasspathBuildingWorksWithSingletonList()
    {
        JasperReportsMojo mojo = new JasperReportsMojo();
        assertEquals( "foo", mojo.buildClasspathString( Collections.singletonList( "foo" ), null ) );
        assertEquals( "foo" + File.pathSeparator + "plop",
                mojo.buildClasspathString( Collections.singletonList( "foo" ), "plop" ) );
    }
}
