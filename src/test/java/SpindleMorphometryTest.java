import de.embl.cba.morphometrics.Utils;
import de.embl.cba.morphometrics.drosophila.shavenbaby.ShavenBabyRegistration;
import de.embl.cba.morphometrics.drosophila.shavenbaby.ShavenBabyRegistrationSettings;
import de.embl.cba.morphometrics.spindle.SpindleMorphometrySettings;
import ij.IJ;
import ij.ImagePlus;
import net.imagej.ImageJ;
import net.imagej.ops.DefaultOpService;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;

import de.embl.cba.morphometrics.spindle.*;

public class SpindleMorphometryTest <T extends RealType<T> & NativeType< T > >
{

	public void run()
	{
		ImageJ imagej = new ImageJ();
		imagej.ui().showUI();

		final String dapiPath = SpindleMorphometryTest.class.getResource( "spindle/test002-C01.tif" ).getFile();
		final String tubulinPath = SpindleMorphometryTest.class.getResource( "spindle/test002-C00.tif" ).getFile();

		final ImagePlus dapiImp = IJ.openImage( dapiPath );
		final ImagePlus tubulinImp = IJ.openImage( tubulinPath );

		final double[] calibration = Utils.getCalibration( dapiImp );

		final Img< T > dapi = ImageJFunctions.wrapReal( dapiImp );
		final Img< T > tubulin = ImageJFunctions.wrapReal( tubulinImp );

		SpindleMorphometrySettings settings = new SpindleMorphometrySettings();
		settings.showIntermediateResults = true;
		settings.inputCalibration = Utils.getCalibration( dapiImp );
		settings.dapi = dapi;
		settings.tubulin = tubulin;
		settings.workingVoxelSize = 0.25;
		settings.maxValue = Math.pow( 2, dapiImp.getBitDepth() ) - 1.0;
		settings.maxShortAxisDist = 6;
		settings.thresholdInUnitsOfBackgroundPeakHalfWidth = 5.0;
		settings.watershedSeedsLocalMaximaDistanceThreshold = 1.0;
		settings.watershedSeedsGlobalDistanceThreshold = 2.0;

		SpindleMorphometry morphometry = new SpindleMorphometry( settings, imagej.op() );
		morphometry.run();

	}

	public static void main( String... args )
	{
		new SpindleMorphometryTest().run();

	}
}
