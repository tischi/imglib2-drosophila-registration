package de.embl.cba.morphometrics;

import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

public class IntensityHistogram <T extends RealType<T> & NativeType< T > >
{
	public double[] binCenters;
	public double[] frequencies;
	final public double binWidth;
	final public int numBins;
	final RandomAccessibleInterval< T > rai;

	public IntensityHistogram( RandomAccessibleInterval< T > rai, double maxValue, double binWidth )
	{
		this.binWidth = binWidth;
		this.numBins = ( int ) ( maxValue / binWidth );
		this.rai = rai;

		initializeHistogram( numBins, binWidth );
		computeFrequencies();
	}

	public void initializeHistogram( int numBins, double binWidth )
	{
		this.binCenters = new double[ numBins ];
		this.frequencies = new double[ numBins ];

		for ( int i = 0; i < numBins; ++i )
		{
			binCenters[ i ] = i * binWidth + binWidth * 0.5;
		}
	}



	public PositionAndValue getMode( )
	{
		final PositionAndValue positionAndValue = new PositionAndValue();

		for ( int i = 0; i < numBins; ++i )
		{
			if ( frequencies[ i ] > positionAndValue.value )
			{
				positionAndValue.value = frequencies[ i ];
				positionAndValue.position = binCenters[ i ];
			}
		}

		return positionAndValue;

	}


	public PositionAndValue getRightHandHalfMaximum( )
	{
		final PositionAndValue maximum = getMode();

		final PositionAndValue positionAndValue = new PositionAndValue();

		for ( int i = 0; i < numBins; ++i )
		{
			if ( binCenters[ i ] > maximum.position )
			{
				if ( frequencies[ i ] <= maximum.value / 2.0 )
				{
					positionAndValue.position = binCenters[ i ];
					positionAndValue.value = frequencies[ i ];
					return positionAndValue;
				}
			}
		}

		return positionAndValue;

	}


	private void computeFrequencies()
	{
		final Cursor< T > cursor = Views.iterable( rai ).cursor();

		while( cursor.hasNext() )
		{
			increment( cursor.next().getRealDouble() );
		}
	}

	public void increment( double value )
	{
		int bin = (int) ( value / binWidth );

		if ( bin >= numBins )
		{
			bin = numBins - 1;
		}

		frequencies[ bin ]++;
	}

}
