package de.embl.cba.drosophila.shavenbaby;

import bdv.util.*;
import de.embl.cba.drosophila.Projection;
import de.embl.cba.drosophila.Transforms;
import de.embl.cba.drosophila.Utils;
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

import static de.embl.cba.drosophila.Constants.*;
import static de.embl.cba.drosophila.Constants.X;
import static de.embl.cba.drosophila.Constants.Y;
import static de.embl.cba.drosophila.ImageIO.openWithBioFormats;


@Plugin(type = Command.class, menuPath = "Plugins>Registration>EMBL>Drosophila Shavenbaby" )
public class ShavenBabyRegistrationCommand<T extends RealType<T> & NativeType< T > > implements Command
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

	ShavenBabyRegistrationSettings settings = new ShavenBabyRegistrationSettings();

	public static final String FROM_DIRECTORY = "From directory";
	public static final String CURRENT_IMAGE = "Current image";

	@Parameter( choices = { FROM_DIRECTORY, CURRENT_IMAGE })
	public String inputModality = FROM_DIRECTORY;

	@Parameter
	public String fileNameEndsWith = "downscaled-svb.tif";

	@Parameter
	public int shavenBabyChannelIndexOneBased = settings.shavenBabyChannelIndexOneBased;

	@Parameter( persist = false )
	public boolean showIntermediateResults = settings.showIntermediateResults;

	@Parameter
	public double registrationResolution = settings.registrationResolution;

	@Parameter
	public double outputResolution = settings.outputResolution;

	@Parameter
	public double backgroundIntensity = settings.backgroundIntensity;

	@Parameter( choices = { ShavenBabyRegistrationSettings.HUANG_AUTO_THRESHOLD, ShavenBabyRegistrationSettings.MANUAL_THRESHOLD } )
	public String thresholdModality = settings.thresholdModality;

	@Parameter
	public double threshold = settings.threshold;

	@Parameter
	public double refractiveIndexScalingCorrectionFactor = settings.refractiveIndexScalingCorrectionFactor;

	@Parameter
	public double refractiveIndexIntensityCorrectionDecayLength = settings.refractiveIndexIntensityCorrectionDecayLength;

	@Parameter
	public double closingRadius = settings.closingRadius;


	public void run()
	{
		setSettingsFromUI();

		final ShavenBabyRegistration registration = new ShavenBabyRegistration( settings, opService );

		if ( inputModality.equals( CURRENT_IMAGE ) && imagePlus != null )
		{
			RandomAccessibleInterval< T > transformed = createTransformedImage( imagePlus, registration );
			showWithBdv( transformed, "registered" );
			ImageJFunctions.show( Views.permute( transformed, 2, 3 ) );
		}

		if ( inputModality.equals( FROM_DIRECTORY ) )
		{
			final File directory = uiService.chooseFile( null, FileWidget.DIRECTORY_STYLE );
			String[] files = directory.list();

			// for each name in the path array
			for( String file : files )
			{

				if ( file.endsWith( fileNameEndsWith ) )
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

					RandomAccessibleInterval< T > transformed = createTransformedImage( imagePlus, registration );

					if ( settings.showIntermediateResults ) showWithBdv( transformed, "registered" );

					final FinalInterval interval = createOutputImageInterval( transformed );

					final IntervalView< T > transformedCropped = Views.interval( transformed, interval );

					if ( settings.showIntermediateResults ) showWithBdv( transformedCropped, "registered cropped" );

					final RandomAccessibleInterval< T > transformedWithImagePlusDimensionOrder = Utils.copyAsArrayImg( Views.permute( transformedCropped, 2, 3 ) );


					Utils.log( "Creating projections..." );
					final ArrayList< ImagePlus > projections = createProjections( transformedWithImagePlusDimensionOrder );

					Utils.log( "Saving projections..." );
					saveImages( inputPath, projections );

					final ImagePlus transformedImagePlus = ImageJFunctions.wrap( transformedWithImagePlusDimensionOrder, "transformed" );

					// Save
					final String outputPath = inputPath + "-registered.tif";
					Utils.log( "Saving registered image: " + outputPath );
					FileSaver fileSaver = new FileSaver( transformedImagePlus );
					fileSaver.saveAsTiff( outputPath );

				}
			}
		}

		Utils.log( "Done!" );


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

	public ArrayList< ImagePlus > createProjections( RandomAccessibleInterval< T > image )
	{
		int Z = 2;

		long zMin = (long) ( 60 / settings.outputResolution );

		ArrayList< ImagePlus > projections = new ArrayList<>(  );

		for ( int channelId = 0; channelId < image.dimension( Utils.imagePlusChannelDimension ); ++channelId )
		{

			RandomAccessibleInterval channel = Views.hyperSlice( image, Utils.imagePlusChannelDimension, channelId );
			channel = Views.translate( channel, new long[]{ 0, 0, image.min( Z ) } );

			Projection projection = new Projection( channel, Z, zMin, channel.max( Z ) );

			final RandomAccessibleInterval maximum = projection.maximum();
			final ImagePlus wrap = ImageJFunctions.wrap( maximum, "projection-C" + channelId );

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

	public RandomAccessibleInterval< T > createTransformedImage( ImagePlus imagePlus, ShavenBabyRegistration registration )
	{
		RandomAccessibleInterval< T > allChannels = ImageJFunctions.wrap( imagePlus );
		RandomAccessibleInterval< T > svb = getShavenBabyImage( imagePlus.getNChannels(), allChannels );

		Utils.log( "Computing registration...." );
		final AffineTransform3D registrationTransform = registration.computeRegistration( svb, Utils.getCalibration( imagePlus ) );

		Utils.log( "Transforming all channels to a final resolution of " + settings.outputResolution + " micrometer ..." );
		final RandomAccessibleInterval< T > allChannelsTransformed = Transforms.transformAllChannels( allChannels, registrationTransform, imagePlus.getNChannels() );

		return allChannelsTransformed;
	}

	public RandomAccessibleInterval< T > getShavenBabyImage( int numChannels, RandomAccessibleInterval< T > allChannels )
	{
		RandomAccessibleInterval< T > svb;

		if ( numChannels > 1 )
		{
			svb = Views.hyperSlice( allChannels, Utils.imagePlusChannelDimension, shavenBabyChannelIndexOneBased - 1 );
		}
		else
		{
			svb = allChannels;
		}
		return svb;
	}

	public void setSettingsFromUI()
	{
		settings.showIntermediateResults = showIntermediateResults;
		settings.registrationResolution = registrationResolution;
		settings.closingRadius = closingRadius;
		settings.outputResolution = outputResolution;
		settings.backgroundIntensity = backgroundIntensity;
		settings.refractiveIndexScalingCorrectionFactor = refractiveIndexScalingCorrectionFactor;
		settings.refractiveIndexIntensityCorrectionDecayLength = refractiveIndexIntensityCorrectionDecayLength;
		settings.thresholdModality = thresholdModality;
		settings.threshold = threshold;
	}


}
