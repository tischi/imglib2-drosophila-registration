package de.embl.cba.morphometrics;

import net.imglib2.*;
import net.imglib2.concatenate.Concatenable;
import net.imglib2.concatenate.PreConcatenable;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.*;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

import java.util.ArrayList;

import static de.embl.cba.morphometrics.Constants.XYZ;

public abstract class Transforms< T extends InvertibleRealTransform & Concatenable< T > & PreConcatenable< T > >
{
    public static RealTransform translationAsRealTransform( double[] translation )
    {
        if ( translation.length == 1 ) return new Translation1D( translation );

        if ( translation.length == 2 ) return new Translation2D( translation );

        if ( translation.length == 3 ) return new Translation3D( translation );

        return new Translation( translation );
    }


    public static < T extends InvertibleRealTransform & Concatenable< T > & PreConcatenable< T > >
    RealTransform createIdentityAffineTransformation( int numDimensions )
    {
        if ( numDimensions == 2 )
        {
            return (T) new AffineTransform2D();
        }
        else if ( numDimensions == 3 )
        {
            return (T) new AffineTransform3D();
        }
        else
        {
            return (T) new AffineTransform( numDimensions );
        }
    }


    public static < T extends NumericType< T > & NativeType< T > >
	RandomAccessibleInterval createTransformedView( RandomAccessibleInterval< T > rai,
													InvertibleRealTransform combinedTransform,
													InterpolatorFactory interpolatorFactory)
	{
		final RandomAccessible transformedRA = createTransformedRaView( rai, combinedTransform, interpolatorFactory );
		final FinalInterval transformedInterval = createTransformedInterval( rai, combinedTransform );
		final RandomAccessibleInterval< T > transformedIntervalView = Views.interval( transformedRA, transformedInterval );

		return transformedIntervalView;

	}

	public static < T extends NumericType< T > & NativeType< T > >
	RandomAccessibleInterval createTransformedView( RandomAccessibleInterval< T > rai,
													InvertibleRealTransform transform )
	{
		final RandomAccessible transformedRA = createTransformedRaView( rai, transform, new NLinearInterpolatorFactory() );
		final FinalInterval transformedInterval = createTransformedInterval( rai, transform );
		final RandomAccessibleInterval< T > transformedIntervalView = Views.interval( transformedRA, transformedInterval );

		return transformedIntervalView;

	}

	public static < S extends NumericType< S >, T extends NumericType< T > >
	RandomAccessibleInterval< T > getWithAdjustedOrigin( RandomAccessibleInterval< S > source, RandomAccessibleInterval< T > target )
	{
		long[] offset = new long[ source.numDimensions() ];
		source.min( offset );
		RandomAccessibleInterval translated = Views.translate( target, offset );
		return translated;
	}

	public static < T extends NumericType< T > >
	RandomAccessible createTransformedRaView( RandomAccessibleInterval< T > rai, InvertibleRealTransform combinedTransform, InterpolatorFactory interpolatorFactory )
	{
		RealRandomAccessible rra = Views.interpolate( Views.extendZero( rai ), interpolatorFactory );

		rra = RealViews.transform( rra, combinedTransform );
		return Views.raster( rra );
	}

    public static < T extends NumericType< T > >
	FinalInterval createTransformedInterval( RandomAccessibleInterval< T > rai, InvertibleRealTransform transform )
	{
		final FinalInterval transformedInterval;

		if ( transform instanceof  AffineTransform3D )
		{
			FinalRealInterval transformedRealInterval = ( ( AffineTransform3D ) transform ).estimateBounds( rai );
			transformedInterval = toInterval( transformedRealInterval );
		}
		else if ( transform instanceof Scale )
		{
			transformedInterval = createScaledInterval( rai, ( Scale ) transform );
		}
		else
		{
			transformedInterval = null;
		}

		return transformedInterval;
	}

	public static < T extends NumericType< T > >
	FinalInterval createScaledInterval( RandomAccessibleInterval< T > rai, Scale scale )
	{
		long[] min = new long[ 3 ];
		long[] max = new long[ 3 ];
		rai.min( min );
		rai.max( max );

		for ( int d : XYZ )
		{
			min[ d ] *= scale.getScale( d );
			max[ d ] *= scale.getScale( d );
		}

		return new FinalInterval( min, max );
	}

	public static FinalInterval toInterval( FinalRealInterval realInterval )
	{
		double[] realMin = new double[ 3 ];
		double[] realMax = new double[ 3 ];
		realInterval.realMin( realMin );
		realInterval.realMax( realMax );

		long[] min = new long[ 3 ];
		long[] max = new long[ 3 ];

		for ( int d : XYZ )
		{
			min[ d ] = (long) realMin[ d ];
			max[ d ] = (long) realMax[ d ];
		}

		return new FinalInterval( min, max );
	}

	public static AffineTransform3D getTransformToIsotropicRegistrationResolution( double binning, double[] calibration )
	{
		double[] downScaling = new double[ 3 ];

		for ( int d : XYZ )
		{
			downScaling[ d ] = calibration[ d ] / binning;
		}

		final AffineTransform3D scalingTransform = createScalingTransform( downScaling );

		return scalingTransform;
	}


	public static double[] getScalingFactors( double[] calibration, double targetResolution )
	{

		double[] downScaling = new double[ calibration.length ];

		for ( int d = 0; d < calibration.length; ++d )
		{
			downScaling[ d ] = calibration[ d ] / targetResolution;
		}

		return downScaling;
	}

	public static AffineTransform3D getScalingTransform( double[] calibration, double targetResolution )
	{

		AffineTransform3D scaling = new AffineTransform3D();

		for ( int d : XYZ )
		{
			scaling.set( calibration[ d ] / targetResolution, d, d );
		}

		return scaling;
	}



	public static AffineTransform3D createScalingTransform( double[] calibration )
	{
		AffineTransform3D scaling = new AffineTransform3D();

		for ( int d : XYZ )
		{
			scaling.set( calibration[ d ], d, d );
		}

		return scaling;
	}

	public static <T extends RealType<T> & NativeType< T > >
	RandomAccessibleInterval< T > transformAllChannels( RandomAccessibleInterval< T > images, AffineTransform3D registrationTransform )
	{
		ArrayList< RandomAccessibleInterval< T > > transformedChannels = new ArrayList<>(  );

		long numChannels = images.dimension( 3 );

		for ( int c = 0; c < numChannels; ++c )
		{
			final RandomAccessibleInterval< T > channel = Views.hyperSlice( images, 3, c );
			transformedChannels.add( createTransformedView( channel, registrationTransform ) );
		}

		return Views.stack( transformedChannels );
	}
}
