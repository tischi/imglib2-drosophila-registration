package de.embl.cba.morphometrics.spindle;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;

public class SpindleMorphometrySettings <T extends RealType<T> & NativeType< T > >
{
	public static final String MANUAL_THRESHOLD = "Manual threshold";
	public static final String HUANG_AUTO_THRESHOLD = "Huang auto threshold";

	// all spatial values are in micrometer
	// morphometrics length: 420
	// morphometrics width: 160

	public static double drosophilaLength = 420;
	public static double drosophilaWidth = 160;

	public int shavenBabyChannelIndexOneBased = 1;
	public boolean showIntermediateResults = false;
	public double refractiveIndexScalingCorrectionFactor = 1.6;
	public double workingVoxelSize = 6.0;
	public double outputResolution = 2.0;
	public double backgroundIntensity = 3155; // TODO: determine from image (maybe min value after averaging)
	public double refractiveIndexIntensityCorrectionDecayLength = 170;

	public double rollAngleMinDistanceToAxis = 0;
	public double rollAngleMinDistanceToCenter = drosophilaLength / 2.0 * 0.5;
	public double rollAngleMaxDistanceToCenter = drosophilaLength / 2.0 - 10.0;

	public double watershedSeedsGlobalDistanceThreshold = drosophilaWidth / 3.0;
	public double watershedSeedsLocalMaximaDistanceThreshold = 3 * workingVoxelSize; // at least 3 pixels

	public String thresholdModality = MANUAL_THRESHOLD;
	public double thresholdInUnitsOfBackgroundPeakHalfWidth = 5.0;
	public double closingRadius = 1.0;

	public double outputImageSizeX = 500;
	public double outputImageSizeY = 250;
	public double outputImageSizeZ = 250;


	public double[] inputCalibration;
	public RandomAccessibleInterval<T> dapi;
	public RandomAccessibleInterval<T> tubulin;

	public double maxValue;
}
