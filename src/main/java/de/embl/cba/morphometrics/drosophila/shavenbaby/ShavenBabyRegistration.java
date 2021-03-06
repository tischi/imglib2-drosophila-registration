package de.embl.cba.morphometrics.drosophila.shavenbaby;

import de.embl.cba.morphometrics.*;
import de.embl.cba.morphometrics.geometry.CentroidsParameters;
import de.embl.cba.morphometrics.geometry.CoordinatesAndValues;
import de.embl.cba.morphometrics.geometry.EllipsoidParameters;
import de.embl.cba.morphometrics.geometry.Ellipsoids;
import net.imagej.ops.OpService;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.algorithm.morphology.distance.DistanceTransform;
import net.imglib2.algorithm.neighborhood.HyperSphereShape;
import net.imglib2.converter.Converters;
import net.imglib2.histogram.Histogram1d;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.roi.labeling.ImgLabeling;
import net.imglib2.roi.labeling.LabelRegion;
import net.imglib2.roi.labeling.LabelRegions;
import net.imglib2.roi.labeling.LabelingType;
import net.imglib2.type.NativeType;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.algorithm.neighborhood.Shape;
import net.imglib2.algorithm.morphology.Closing;

import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

import java.util.*;

import static de.embl.cba.morphometrics.Constants.X;
import static de.embl.cba.morphometrics.Constants.XYZ;
import static de.embl.cba.morphometrics.Constants.Z;
import static de.embl.cba.morphometrics.Transforms.getScalingFactors;
import static de.embl.cba.morphometrics.viewing.BdvImageViewer.show;
import static java.lang.Math.toRadians;


public class ShavenBabyRegistration
{

	final ShavenBabyRegistrationSettings settings;
	final OpService opService;

	public ShavenBabyRegistration( ShavenBabyRegistrationSettings settings, OpService opService )
	{
		this.settings = settings;
		this.opService = opService;
	}

	public < T extends RealType< T > & NativeType< T > >
	AffineTransform3D computeRegistration( RandomAccessibleInterval< T > input, double[] inputCalibration  )
	{

		AffineTransform3D registration = new AffineTransform3D();

		double[] registrationCalibration = Utils.get3dDoubleArray( settings.registrationResolution );

		Utils.log( "Refractive index scaling correction..." );

		RefractiveIndexMismatchCorrections.correctCalibration( inputCalibration, settings.refractiveIndexScalingCorrectionFactor );


		/**
		 *  Down-sampling to registration resolution
		 */

		
		Utils.log( "Down-sampling to registration resolution..." );

		final RandomAccessibleInterval< T > downscaled = Algorithms.createIsotropicArrayImg( input, getScalingFactors( inputCalibration, settings.registrationResolution ) );

		if ( settings.showIntermediateResults ) show( downscaled, "at registration resolution", null, registrationCalibration, false );


		/**
		 *  Compute offset and threshold
		 */


		Utils.log( "Computing offset and threshold..." );

		final IntensityHistogram intensityHistogram = new IntensityHistogram( downscaled, 65535.0, 5.0 );

		PositionAndValue mode = intensityHistogram.getMode();

		final PositionAndValue rightHandHalfMaximum = intensityHistogram.getRightHandHalfMaximum();

		double thresholdAfterIntensityCorrection = ( rightHandHalfMaximum.position - mode.position ) * settings.thresholdInUnitsOfBackgroundPeakHalfWidth;

		Utils.log( "Offset: " + mode.position );
		Utils.log( "Threshold: " + ( thresholdAfterIntensityCorrection + mode.position ) );

		/**
		 *  Refractive index corrections
		 */
		
		Utils.log( "Refractive index intensity correction..." );

		final RandomAccessibleInterval< T > intensityCorrected = Utils.copyAsArrayImg( downscaled );

		RefractiveIndexMismatchCorrections.correctIntensity( intensityCorrected, registrationCalibration[ Z ], mode.position, settings.refractiveIndexIntensityCorrectionDecayLength );

		if ( settings.showIntermediateResults ) show( intensityCorrected, "intensity corrected", null, registrationCalibration, false );


		/**
		 * Create mask
		 */

		RandomAccessibleInterval< BitType > mask = createMask( intensityCorrected, thresholdAfterIntensityCorrection );

		if ( settings.showIntermediateResults ) show( mask, "mask", null, registrationCalibration, false );


		/**
		 * Morphological closing
		 */

		RandomAccessibleInterval< BitType > closed = createClosedImage( mask );

		if ( settings.showIntermediateResults ) show( closed, "closed", null, registrationCalibration, false );

		/**
		 * Distance transform
		 *
		 * Note: EUCLIDIAN distances are returned as squared distances
		 */

		Utils.log( "Distance transform..." );

		final RandomAccessibleInterval< DoubleType > doubleBinary = Converters.convert( closed, ( i, o ) -> o.set( i.get() ? Double.MAX_VALUE : 0 ), new DoubleType() );

		final RandomAccessibleInterval< DoubleType > distance = ArrayImgs.doubles( Intervals.dimensionsAsLongArray( doubleBinary ) );

		DistanceTransform.transform( doubleBinary, distance, DistanceTransform.DISTANCE_TYPE.EUCLIDIAN, 1.0D );

		if ( settings.showIntermediateResults )
			show( distance, "distance transform", null, registrationCalibration, false );

		/**
		 * Watershed seeds
		 */

		final ImgLabeling< Integer, IntType > seedsLabelImg = createWatershedSeeds( registrationCalibration, distance, closed );


		/**
		 * Watershed
		 */

		Utils.log( "Watershed..." );

		// prepare result label image
		final Img< IntType > watershedLabelImg = ArrayImgs.ints( Intervals.dimensionsAsLongArray( mask ) );
		final ImgLabeling< Integer, IntType > watershedLabeling = new ImgLabeling<>( watershedLabelImg );

		opService.image().watershed(
				watershedLabeling,
				Utils.invertedView( distance ),
				seedsLabelImg,
				false,
				false );

		Utils.applyMask( watershedLabelImg, closed );

		if ( settings.showIntermediateResults ) show( watershedLabelImg, "watershed", null, registrationCalibration, false );


		/**
		 * Get central embryo
		 */

		Utils.log( "Get central embryo..." );

		final LabelRegion< Integer > centralObjectRegion = getCentralObjectLabelRegion( watershedLabeling );

		final Img< BitType > centralObjectMask = createBitTypeMaskFromLabelRegion( centralObjectRegion, Intervals.dimensionsAsLongArray( downscaled ) );

		if ( settings.showIntermediateResults )
			show( centralObjectMask, "central object", null, registrationCalibration, false );

		/**
		 * Compute ellipsoid (probably mainly yaw) alignment
		 */

		Utils.log( "Fit ellipsoid..." );

		final EllipsoidParameters ellipsoidParameters = Ellipsoids.computeParametersFromBinaryImage( centralObjectMask );

		registration.preConcatenate( Ellipsoids.createAlignmentTransform( ellipsoidParameters ) );

		final RandomAccessibleInterval yawAlignedMask = Utils.copyAsArrayImg( Transforms.createTransformedView( centralObjectMask, registration, new NearestNeighborInterpolatorFactory() ) );

		final RandomAccessibleInterval yawAlignedIntensities = Utils.copyAsArrayImg( Transforms.createTransformedView( downscaled, registration ) );


		/**
		 *  Long axis orientation
		 */

		Utils.log( "Computing long axis orientation..." );

		final AffineTransform3D orientationTransform = computeOrientationTransform( yawAlignedMask, yawAlignedIntensities, settings.registrationResolution );

		registration = registration.preConcatenate( orientationTransform );


		/**
		 *  Roll transform
		 */

		Utils.log( "Computing roll transform..." );

		final RandomAccessibleInterval yawAndOrientationAlignedMask = Utils.copyAsArrayImg( Transforms.createTransformedView( centralObjectMask, registration, new NearestNeighborInterpolatorFactory() ) );

		final CentroidsParameters centroidsParameters = Utils.computeCentroidsParametersAlongXAxis( yawAndOrientationAlignedMask, settings.registrationResolution, settings.rollAngleMaxDistanceToCenter );

		if ( settings.showIntermediateResults )
			Plots.plot( centroidsParameters.axisCoordinates, centroidsParameters.angles, "x", "angle" );
		if ( settings.showIntermediateResults )
			Plots.plot( centroidsParameters.axisCoordinates, centroidsParameters.distances, "x", "distance" );
		if ( settings.showIntermediateResults )
			Plots.plot( centroidsParameters.axisCoordinates, centroidsParameters.numVoxels, "x", "numVoxels" );
		if ( settings.showIntermediateResults )
			show( yawAndOrientationAlignedMask, "yaw and orientation aligned mask", centroidsParameters.centroids, registrationCalibration, false );

		final AffineTransform3D rollTransform = computeRollTransform( centroidsParameters, settings );

		registration = registration.preConcatenate( rollTransform );

		ArrayList< RealPoint > transformedCentroids = createTransformedCentroidPointList( centroidsParameters, rollTransform );

		if ( settings.showIntermediateResults )
			show( Transforms.createTransformedView( centralObjectMask, registration, new NearestNeighborInterpolatorFactory() ), "yaw and roll aligned mask", transformedCentroids, registrationCalibration, false );

		/**
		 * Compute final registration
		 */

		registration = createFinalTransform( inputCalibration, registration, registrationCalibration );

		if ( settings.showIntermediateResults )
			show( Transforms.createTransformedView( intensityCorrected, registration ), "aligned input data ( " + settings.outputResolution + " um )", origin(), registrationCalibration, false );

		return registration;

	}

	public RandomAccessibleInterval< BitType > createClosedImage( RandomAccessibleInterval< BitType > mask )
	{
		RandomAccessibleInterval< BitType > closed = Utils.copyAsArrayImg( mask );

		if ( settings.closingRadius > 0 )
		{
			Utils.log( "Morphological closing...");
			Shape closingShape = new HyperSphereShape( ( int ) ( settings.closingRadius / settings.registrationResolution ) );
			Closing.close( Views.extendBorder( mask ), Views.iterable( closed ), closingShape, 1 );
		}

		return closed;
	}

	public < T extends RealType< T > & NativeType< T > >
	RandomAccessibleInterval< BitType > createMask( RandomAccessibleInterval< T > downscaled, double threshold )
	{
		Utils.log( "Creating mask...");

		RandomAccessibleInterval< BitType > mask = Converters.convert( downscaled, ( i, o ) -> o.set( i.getRealDouble() > threshold ? true : false ), new BitType() );

		mask = opService.morphology().fillHoles( mask );

		return mask;
	}

	public < T extends RealType< T > & NativeType< T > > double getThreshold( RandomAccessibleInterval< T > downscaled )
	{

		double threshold = 0;

		if ( settings.thresholdModality.equals( ShavenBabyRegistrationSettings.HUANG_AUTO_THRESHOLD ) )
		{
			final Histogram1d< T > histogram = opService.image().histogram( Views.iterable( downscaled ) );

			double huang = opService.threshold().huang( histogram ).getRealDouble();
			double yen = opService.threshold().yen( histogram ).getRealDouble();

			threshold = huang;
		}
		else
		{
			threshold= settings.thresholdInUnitsOfBackgroundPeakHalfWidth;
		}
		return threshold;
	}

	public ImgLabeling< Integer, IntType > createWatershedSeeds( double[] registrationCalibration, RandomAccessibleInterval< DoubleType > distance, RandomAccessibleInterval< BitType > mask )
	{
		Utils.log( "Seeds for watershed...");

		double globalDistanceThreshold = Math.pow( settings.watershedSeedsGlobalDistanceThreshold / settings.registrationResolution, 2 );
		double localMaximaDistanceThreshold = Math.pow( settings.watershedSeedsLocalMaximaDistanceThreshold / settings.registrationResolution, 2 );

		final RandomAccessibleInterval< BitType >  seeds = Utils.createSeeds(
				distance,
				new HyperSphereShape( 1 ),
				globalDistanceThreshold,
				localMaximaDistanceThreshold );

		final ImgLabeling< Integer, IntType > seedsLabelImg = Utils.createLabelImg( seeds );

		if ( settings.showIntermediateResults ) show( Utils.asIntImg( seedsLabelImg ), "watershed seeds", null, registrationCalibration, false );
		return seedsLabelImg;
	}

	public AffineTransform3D computeOrientationTransform( RandomAccessibleInterval yawAlignedMask, RandomAccessibleInterval yawAlignedIntensities, double calibration )
	{
		final CoordinatesAndValues coordinatesAndValues = Utils.computeAverageIntensitiesAlongAxis( yawAlignedIntensities, yawAlignedMask, X, calibration );

		if ( settings.showIntermediateResults ) Plots.plot( coordinatesAndValues.coordinates, coordinatesAndValues.values, "x", "average intensity" );

		double maxLoc = Utils.computeMaxLoc( coordinatesAndValues.coordinates, coordinatesAndValues.values );

		AffineTransform3D affineTransform3D = new AffineTransform3D();

		if ( maxLoc < 0 ) affineTransform3D.rotate( Z, toRadians( 180.0D ) );

		return affineTransform3D;
	}

	public ArrayList< RealPoint > createTransformedCentroidPointList( CentroidsParameters centroidsParameters, AffineTransform3D rollTransform )
	{
		final ArrayList< RealPoint > transformedRealPoints = new ArrayList<>();

		for ( RealPoint realPoint : centroidsParameters.centroids )
		{
			final RealPoint transformedRealPoint = new RealPoint( 0, 0, 0 );
			rollTransform.apply( realPoint, transformedRealPoint );
			transformedRealPoints.add( transformedRealPoint );
		}
		return transformedRealPoints;
	}

	public static AffineTransform3D computeRollTransform( CentroidsParameters centroidsParameters, ShavenBabyRegistrationSettings settings )
	{
		final double rollAngle = computeRollAngle( centroidsParameters, settings.rollAngleMinDistanceToAxis, settings.rollAngleMinDistanceToCenter, settings.rollAngleMaxDistanceToCenter );

		Utils.log( "Roll angle " + rollAngle );

		AffineTransform3D rollTransform = new AffineTransform3D();

		rollTransform.rotate( X, - toRadians( rollAngle ) );

		return rollTransform;
	}

	public static double computeRollAngle( CentroidsParameters centroidsParameters, double minDistanceToAxis, double minDistanceToCenter, double maxDistanceToCenter )
	{
		final int n = centroidsParameters.axisCoordinates.size();

		List< Double> offCenterAngles = new ArrayList<>(  );

		for ( int i = 0; i < n; ++i )
		{
			if ( ( centroidsParameters.distances.get( i ) > minDistanceToAxis ) &&
					( Math.abs(  centroidsParameters.axisCoordinates.get( i ) ) > minDistanceToCenter ) &&
					( Math.abs(  centroidsParameters.axisCoordinates.get( i ) ) < maxDistanceToCenter ))
			{
				offCenterAngles.add( centroidsParameters.angles.get( i ) );
			}
		}

		Collections.sort( offCenterAngles );

		double medianAngle = Utils.median( offCenterAngles );

		return medianAngle;
	}

	public static ArrayList< RealPoint > origin()
	{
		final ArrayList< RealPoint > origin = new ArrayList<>();
		origin.add( new RealPoint( new double[]{ 0, 0, 0 } ) );
		return origin;
	}

	private AffineTransform3D createFinalTransform( double[] inputCalibration, AffineTransform3D registration, double[] registrationCalibration )
	{
		final AffineTransform3D transform =
				Transforms.getScalingTransform( inputCalibration, settings.registrationResolution )
				.preConcatenate( registration )
				.preConcatenate( Transforms.getScalingTransform( registrationCalibration, settings.outputResolution ) );

		return transform;
	}

	private Img< BitType > createBitTypeMaskFromLabelRegion( LabelRegion< Integer > centralObjectRegion, long[] dimensions )
	{
		final Img< BitType > centralObjectImg = ArrayImgs.bits( dimensions );

		final Cursor< Void > regionCursor = centralObjectRegion.cursor();
		final net.imglib2.RandomAccess< BitType > access = centralObjectImg.randomAccess();
		while ( regionCursor.hasNext() )
		{
			regionCursor.fwd();
			access.setPosition( regionCursor );
			access.get().set( true );
		}
		return centralObjectImg;
	}

	private Img< UnsignedByteType > createUnsignedByteTypeMaskFromLabelRegion( LabelRegion< Integer > centralObjectRegion, long[] dimensions )
	{
		final Img< UnsignedByteType > centralObjectImg = ArrayImgs.unsignedBytes( dimensions );

		final Cursor< Void > regionCursor = centralObjectRegion.cursor();
		final net.imglib2.RandomAccess< UnsignedByteType > access = centralObjectImg.randomAccess();
		while ( regionCursor.hasNext() )
		{
			regionCursor.fwd();
			access.setPosition( regionCursor );
			access.get().set( 255 );
		}
		return centralObjectImg;
	}


	private LabelRegion< Integer > getCentralObjectLabelRegion( ImgLabeling< Integer, IntType > labeling )
	{
		int centralLabel = getCentralLabel( labeling );

		final LabelRegions< Integer > labelRegions = new LabelRegions<>( labeling );

		return labelRegions.getLabelRegion( centralLabel );
	}

	private static int getCentralLabel( ImgLabeling< Integer, IntType > labeling )
	{
		final net.imglib2.RandomAccess< LabelingType< Integer > > labelingRandomAccess = labeling.randomAccess();
		for ( int d : XYZ ) labelingRandomAccess.setPosition( labeling.dimension( d ) / 2, d );
		int centralIndex = labelingRandomAccess.get().getIndex().getInteger();
		return labeling.getMapping().labelsAtIndex( centralIndex ).iterator().next();
	}

	//
	// Useful code snippets
	//

	/**
	 *  Distance transformAllChannels

	 Hi Christian

	 yes, it seems that you were doing the right thing (as confirmed by your
	 visual inspection of the result). One thing to note: You should
	 probably use a DoubleType image with 1e20 and 0 values, to make sure
	 that f(q) is larger than any possible distance in your image. If you
	 choose 255, your distance is effectively bounded at 255. This can be an
	 issue for big images with sparse foreground objects. With squared
	 Euclidian distance, 255 is already reached if a background pixels is
	 further than 15 pixels from a foreground pixel! If you use
	 Converters.convert to generate your image, the memory consumption
	 remains the same.


	 Phil

	 final RandomAccessibleInterval< UnsignedByteType > binary = Converters.convert(
	 downscaled, ( i, o ) -> o.set( i.getRealDouble() > settings.thresholdInUnitsOfBackgroundPeakHalfWidth ? 255 : 0 ), new UnsignedByteType() );

	 if ( settings.showIntermediateResults ) show( binary, "binary", null, calibration, false );


	 final RandomAccessibleInterval< DoubleType > distance = ArrayImgs.doubles( Intervals.dimensionsAsLongArray( binary ) );

	 DistanceTransform.transformAllChannels( binary, distance, DistanceTransform.DISTANCE_TYPE.EUCLIDIAN );


	 final double maxDistance = Algorithms.getMaximumValue( distance );

	 final RandomAccessibleInterval< IntType > invertedDistance = Converters.convert( distance, ( i, o ) -> {
	 o.set( ( int ) ( maxDistance - i.get() ) );
	 }, new IntType() );

	 if ( settings.showIntermediateResults ) show( invertedDistance, "distance", null, calibration, false );

	 */


	/**
	 * Convert ImgLabelling to Rai

	 final RandomAccessibleInterval< IntType > labelMask =
	 Converters.convert( ( RandomAccessibleInterval< LabelingType< Integer > > ) watershedImgLabeling,
	 ( i, o ) -> {
	 o.set( i.getIndex().getInteger() );
	 }, new IntType() );

	 */




}
