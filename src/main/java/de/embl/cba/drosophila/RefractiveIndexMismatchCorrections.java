package de.embl.cba.drosophila;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

import java.util.ArrayList;

import static de.embl.cba.drosophila.Constants.Z;
import static java.lang.Math.exp;

public abstract class RefractiveIndexMismatchCorrections
{


	public static double getIntensityCorrectionFactorAlongZ( long z, double zScalingToMicrometer, double intensityDecayLengthInMicrometer )
	{

		/*
		f( 10 ) = 93; f( 83 ) = 30;
		f( z1 ) = v1; f( z2 ) = v2;
		f( z ) = A * exp( -z / d );

		=> d = ( z2 - z1 ) / ln( v1 / v2 );

		> log ( 93 / 30 )
		[1] 1.1314

		=> d = 	73 / 1.1314 = 64.52172;

		=> correction = 1 / exp( -z / d );

		at z = 10 we want value = 93 => z0  = 10;

		=> correction = 1 / exp( - ( z - 10 ) / d );
		 */

		double generalIntensityScaling = 0.3; // TODO: what to use here?

		double offsetInMicrometer = 10.0D; // TODO: might differ between samples?

		double zInMicrometer = z * zScalingToMicrometer - offsetInMicrometer;

		double correctionFactor = generalIntensityScaling / exp( - zInMicrometer / intensityDecayLengthInMicrometer );

		return correctionFactor;
	}

	public static < T extends RealType< T > & NativeType< T > >
	void correctIntensity( RandomAccessibleInterval< T > rai, double zCalibration, double intensityOffset, double intensityDecayLength )
	{
		for ( long z = rai.min( Z ); z <= rai.max( Z ); ++z )
		{
			RandomAccessibleInterval< T > slice = Views.hyperSlice( rai, Z, z );

			double intensityCorrectionFactor = getIntensityCorrectionFactorAlongZ( z, zCalibration, intensityDecayLength );

			Views.iterable( slice ).forEach( t ->
					{
						if ( ( t.getRealDouble() - intensityOffset ) < 0 )
						{
							t.setReal( 0 );
						}
						else
						{
							t.setReal( t.getRealDouble() - intensityOffset );
							t.mul( intensityCorrectionFactor );
						}

					}
			);

		}

	}

	public static <T extends RealType<T> & NativeType< T > >
	RandomAccessibleInterval< T > createIntensityCorrectedImages( RandomAccessibleInterval< T > images, double zCalibration, double intensityDecayLength )
	{
		ArrayList< RandomAccessibleInterval< T > > correctedImages = new ArrayList<>(  );

		long numChannels = 1;

		if ( images.numDimensions() > 3 )
		{
			numChannels = images.dimension( Utils.imagePlusChannelDimension );
		}

		if ( numChannels > 1 )
		{
			for ( int c = 0; c < numChannels; ++c )
			{
				final RandomAccessibleInterval< T > channel = Views.hyperSlice( images, Utils.imagePlusChannelDimension, c );
				final RandomAccessibleInterval< T > intensityCorrectedChannel = createIntensityCorrectedChannel( zCalibration, intensityDecayLength, channel );
				correctedImages.add( intensityCorrectedChannel );
			}
		}
		else
		{
			final RandomAccessibleInterval< T > intensityCorrectedChannel = createIntensityCorrectedChannel( zCalibration, intensityDecayLength, images );
			correctedImages.add( intensityCorrectedChannel );
		}

		return Views.stack( correctedImages );
	}

	public static < T extends RealType< T > & NativeType< T > > RandomAccessibleInterval< T > createIntensityCorrectedChannel( double zCalibration, double intensityDecayLength, RandomAccessibleInterval< T > channel )
	{
		final double intensityOffset = getIntensityOffset( channel );
		final RandomAccessibleInterval< T > intensityCorrectedChannel = Utils.copyAsArrayImg( channel );
		correctIntensity( intensityCorrectedChannel, zCalibration, intensityOffset, intensityDecayLength );
		return intensityCorrectedChannel;
	}

	public static < T extends RealType< T > & NativeType< T > > double getIntensityOffset( RandomAccessibleInterval< T > channel )
	{
		final IntensityHistogram intensityHistogram = new IntensityHistogram( channel, 65535, 5 );
		return intensityHistogram.getMode().position;
	}

	public static void correctCalibration( double[] calibration, double correctionFactor )
	{
		calibration[ Z ] *= correctionFactor;
	}
}
