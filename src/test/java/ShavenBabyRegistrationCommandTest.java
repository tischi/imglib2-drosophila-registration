import de.embl.cba.morphometrics.drosophila.shavenbaby.ShavenBabyRegistrationCommand;
import net.imagej.ImageJ;

public class ShavenBabyRegistrationCommandTest
{

	public static void main(final String... args) throws Exception
	{
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();


		// Load and show data
//		String path = "/Users/tischer/Documents/justin-crocker-morphometrics-registration--data/E3NWT-04-downscaled-svb.tif";
//		ImagePlus imp = IJ.openImage( path ); imp.show();

		// invoke the plugin
		ij.command().run( ShavenBabyRegistrationCommand.class, true );
	}

}
