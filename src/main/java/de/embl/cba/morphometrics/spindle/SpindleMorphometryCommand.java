package de.embl.cba.morphometrics.spindle;

import bdv.util.AxisOrder;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import de.embl.cba.morphometrics.Projection;
import de.embl.cba.morphometrics.RefractiveIndexMismatchCorrections;
import de.embl.cba.morphometrics.Transforms;
import de.embl.cba.morphometrics.Utils;
import ij.ImagePlus;
import ij.io.FileSaver;
import net.imagej.DatasetService;
import net.imagej.ops.OpService;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;
import org.scijava.widget.FileWidget;

import java.io.File;
import java.util.ArrayList;

import static de.embl.cba.morphometrics.Constants.*;
import static de.embl.cba.morphometrics.ImageIO.openWithBioFormats;


@Plugin(type = Command.class, menuPath = "Plugins>Registration>EMBL>Drosophila Shavenbaby" )
public class SpindleMorphometryCommand<T extends RealType<T> & NativeType< T > > implements Command
{
	@Parameter
	public UIService uiService;

	@Parameter
	public DatasetService datasetService;

	@Parameter
	public LogService logService;

	@Parameter
	public OpService opService;

	@Parameter
	public StatusService statusService;

	@Parameter( required = false )
	public ImagePlus imagePlus;

	SpindleMorphometrySettings settings = new SpindleMorphometrySettings();

	public static final String FROM_DIRECTORY = "From directory";
	public static final String CURRENT_IMAGE = "Current image";

	@Parameter( choices = { FROM_DIRECTORY, CURRENT_IMAGE })
	public String inputModality = FROM_DIRECTORY;

	@Parameter
	public String fileNameEndsWith = ".czi,.lsm";

	@Parameter
	public int shavenBabyChannelIndexOneBased = settings.shavenBabyChannelIndexOneBased;

	@Parameter
	public boolean showIntermediateResults = settings.showIntermediateResults;

	@Parameter
	public double registrationResolution = settings.workingVoxelSize;

	@Parameter
	public double outputResolution = settings.outputResolution;

	@Parameter
	public double thresholdInUnitsOfBackgroundPeakHalfWidth = settings.thresholdInUnitsOfBackgroundPeakHalfWidth;

	@Parameter
	public double refractiveIndexScalingCorrectionFactor = settings.refractiveIndexScalingCorrectionFactor;

	@Parameter
	public double refractiveIndexIntensityCorrectionDecayLength = settings.refractiveIndexIntensityCorrectionDecayLength;

	public void run()
	{
		setSettingsFromUI();

		final SpindleMorphometry registration = new SpindleMorphometry( settings, opService );

		if ( inputModality.equals( CURRENT_IMAGE ) && imagePlus != null )
		{
//			RandomAccessibleInterval< T > transformed = registerImages( imagePlus, registration );
//			showWithBdv( transformed, "registered" );
//			ImageJFunctions.show( Views.permute( transformed, 2, 3 ) );
		}


		if ( inputModality.equals( FROM_DIRECTORY ) )
		{
			final File directory = uiService.chooseFile( null, FileWidget.DIRECTORY_STYLE );
			String[] files = directory.list();

			for( String file : files )
			{
				if ( acceptFile( fileNameEndsWith, file ) )
				{
					// Open
					final String inputPath = directory + "/" + file;
					Utils.log( "Reading: " + inputPath + "..." );
					final ImagePlus imagePlus = openWithBioFormats( inputPath );

					if ( imagePlus == null )
					{
						logService.error( "Error opening file: " + inputPath );
						continue;
					}

					RandomAccessibleInterval< T > registeredImages = registerImages( imagePlus, registration );

					final FinalInterval interval = createOutputImageInterval( registeredImages );

					final IntervalView< T > registeredAndCroppedView = Views.interval( registeredImages, interval );

					final RandomAccessibleInterval< T > registeredAndCropped = Utils.copyAsArrayImg( registeredAndCroppedView );

					if ( settings.showIntermediateResults ) showWithBdv( registeredAndCropped, "registered" );

					Utils.log( "Creating projections..." );
					final ArrayList< ImagePlus > projections = createProjections( registeredAndCropped );

					Utils.log( "Saving projections..." );
					saveImages( inputPath, projections );

					// Save
					Utils.log( "Transforming registered images to imagePlus for saving..." );
					final RandomAccessibleInterval< T > transformedWithImagePlusDimensionOrder = Utils.copyAsArrayImg( Views.permute( registeredAndCropped, 2, 3 ) );
					final ImagePlus transformedImagePlus = ImageJFunctions.wrap( transformedWithImagePlusDimensionOrder, "transformed" );
					final String outputPath = inputPath + "-registered.tif";
					Utils.log( "Saving registered image: " + outputPath );
					FileSaver fileSaver = new FileSaver( transformedImagePlus );
					fileSaver.saveAsTiff( outputPath );

				}
			}
		}

		Utils.log( "Done!" );


	}

	public boolean acceptFile( String fileNameEndsWith, String file )
	{
		final String[] fileNameEndsWithList = fileNameEndsWith.split( "," );

		for ( String endsWith : fileNameEndsWithList )
		{
			if ( file.endsWith( endsWith.trim() ) )
			{
				return true;
			}
		}

		return false;
	}

	public FinalInterval createOutputImageInterval( RandomAccessibleInterval rai )
	{
		final long[] min = Intervals.minAsLongArray( rai );
		final long[] max = Intervals.maxAsLongArray( rai );

		min[ X ] = - (long) ( settings.outputImageSizeX / 2 / settings.outputResolution );
		min[ Y ] = - (long) ( settings.outputImageSizeY / 2 / settings.outputResolution );
		min[ Z ] = - (long) ( settings.outputImageSizeZ / 2 / settings.outputResolution );

		for ( int d = 0; d < 3; ++d )
		{
			max[ d ] = -1 * min[ d ];
		}

		return new FinalInterval( min, max );
	}

	public void saveImages( String inputPath, ArrayList< ImagePlus > imps )
	{
		for ( ImagePlus imp : imps )
		{
			final String outputPath = inputPath + "-" + imp.getTitle() + ".tif";
			FileSaver fileSaver = new FileSaver( imp );
			fileSaver.saveAsTiff( outputPath );
		}
	}

	public ArrayList< ImagePlus > createProjections( RandomAccessibleInterval< T > images )
	{
		int Z = 2;

		long zMin = (long) ( 60 / settings.outputResolution );

		ArrayList< ImagePlus > projections = new ArrayList<>(  );

		for ( int channelId = 0; channelId < images.dimension( 3 ); ++channelId )
		{

			RandomAccessibleInterval channel = Views.hyperSlice( images, 3, channelId );

			Projection projection = new Projection( channel, Z, zMin, channel.max( Z ) );

			final RandomAccessibleInterval maximum = projection.maximum();
			final ImagePlus wrap = ImageJFunctions.wrap( maximum, "projection-channel" + ( channelId + 1 ) );

			projections.add( wrap );
		}

		return projections;
	}

	public void showWithBdv( RandomAccessibleInterval< T > transformed, String title )
	{
		Bdv bdv = BdvFunctions.show( transformed, title, BdvOptions.options().axisOrder( AxisOrder.XYZC ) );
		final ArrayList< RealPoint > points = new ArrayList<>();
		points.add( new RealPoint( new double[]{0,0,0} ));
		BdvFunctions.showPoints( points, "origin", BdvOptions.options().addTo( bdv ) );
	}

	public RandomAccessibleInterval< T > registerImages( ImagePlus imagePlus, SpindleMorphometry registration )
	{
		RandomAccessibleInterval< T > images = getImages( imagePlus );
		RandomAccessibleInterval< T > shavenBaby = getShavenBabyImage( images );

		final double[] calibration = Utils.getCalibration( imagePlus );

		Utils.log( "Computing registration...." );
		final AffineTransform3D registrationTransform = registration.computeRegistration( shavenBaby, calibration );

		Utils.log( "Applying intensity correction to all channels...." );
		final RandomAccessibleInterval< T > intensityCorrectedImages = RefractiveIndexMismatchCorrections.createIntensityCorrectedImages( images, calibration[ 2 ], settings.refractiveIndexIntensityCorrectionDecayLength  );

		Utils.log( "Applying registration to all channels (at a resolution of " + settings.outputResolution + " micrometer) ..." );
		final RandomAccessibleInterval< T > registeredImages = Transforms.transformAllChannels( intensityCorrectedImages, registrationTransform );

		return registeredImages;
	}

	public RandomAccessibleInterval< T > getImages( ImagePlus imagePlus )
	{
		RandomAccessibleInterval< T > images = ImageJFunctions.wrap( imagePlus );

		int numChannels = imagePlus.getNChannels();

		if ( numChannels == 1 )
		{
			Views.addDimension( images );
		}
		else
		{
			images = Views.permute( images, Utils.imagePlusChannelDimension, 3 );
		}

		return images;
	}

	public RandomAccessibleInterval< T > getShavenBabyImage( RandomAccessibleInterval< T > images )
	{
		RandomAccessibleInterval< T > svb = Views.hyperSlice( images, 3, shavenBabyChannelIndexOneBased - 1 );

		return svb;
	}

	public void setSettingsFromUI()
	{
		settings.showIntermediateResults = showIntermediateResults;
		settings.workingVoxelSize = registrationResolution;
		settings.closingRadius = 0;
		settings.outputResolution = outputResolution;
		settings.backgroundIntensity = 0;
		settings.refractiveIndexScalingCorrectionFactor = refractiveIndexScalingCorrectionFactor;
		settings.refractiveIndexIntensityCorrectionDecayLength = refractiveIndexIntensityCorrectionDecayLength;
		settings.thresholdModality = "";
		settings.thresholdInUnitsOfBackgroundPeakHalfWidth = thresholdInUnitsOfBackgroundPeakHalfWidth;
	}


}
