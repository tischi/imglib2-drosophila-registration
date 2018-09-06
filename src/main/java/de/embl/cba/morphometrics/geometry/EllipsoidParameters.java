package de.embl.cba.morphometrics.geometry;

public class EllipsoidParameters
{

	public static final int PHI = 0, THETA = 1, PSI = 2;

	public double[] center = new double[ 3 ];
	public double[] radii = new double[ 3 ];
	public double[] eulerAnglesInDegrees = new double[ 3 ];

}
