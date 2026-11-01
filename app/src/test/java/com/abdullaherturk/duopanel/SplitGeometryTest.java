package com.abdullaherturk.duopanel;

import android.graphics.Rect;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SplitGeometryTest {
    @Test public void systemBarsAndCutoutAreExcludedFromCoverageAndTargetCoordinates() {
        SplitGeometry geometry = new SplitGeometry(new Rect(20, 24, 980, 376),
                Arrays.asList(new Rect(20, 24, 304, 376)), Arrays.asList(new Rect(312, 24, 980, 376)), null, 1);
        assertTrue(geometry.coversScreen());
        assertEquals(30, geometry.percent());
        assertEquals(308, geometry.targetX(30));
    }

    @Test public void portraitRatioUsesUsableHeightIncludingItsOrigin() {
        SplitGeometry geometry = new SplitGeometry(new Rect(0, 24, 400, 984),
                Arrays.asList(new Rect(0, 24, 400, 308)), Arrays.asList(new Rect(0, 316, 400, 984)), null, 1);
        assertTrue(geometry.coversScreen());
        assertEquals(30, geometry.percent());
        assertEquals(312, geometry.targetY(30));
    }

    @Test public void rightBlackBandIsIncompleteEvenWithAlignedPanesAndCorrectDivider() {
        SplitGeometry geometry = new SplitGeometry(new Rect(0, 0, 1000, 400),
                Arrays.asList(new Rect(0, 0, 296, 400)), Arrays.asList(new Rect(304, 0, 500, 400)),
                new Rect(292, 0, 308, 400), 1);
        assertEquals(30, geometry.percent());
        assertTrue(geometry.isTiled());
        assertFalse(geometry.coversScreen());
    }

    @Test public void outerEdgesAloneCannotHideLargeGapBetweenPanes() {
        SplitGeometry geometry = new SplitGeometry(new Rect(0, 0, 1000, 400),
                Arrays.asList(new Rect(0, 0, 296, 400)), Arrays.asList(new Rect(500, 0, 1000, 400)), null, 1);
        assertFalse(geometry.coversScreen());
    }

    @Test public void overlappingOrShortFreeformWindowIsNotATiledPair() {
        SplitGeometry overlapping = new SplitGeometry(new Rect(0, 0, 1000, 400),
                Arrays.asList(new Rect(0, 0, 600, 400)), Arrays.asList(new Rect(500, 0, 1000, 400)), null, 1);
        SplitGeometry floating = new SplitGeometry(new Rect(0, 0, 1000, 400),
                Arrays.asList(new Rect(0, 0, 296, 400)), Arrays.asList(new Rect(304, 60, 1000, 340)), null, 1);
        assertFalse(overlapping.isTiled());
        assertFalse(floating.isTiled());
        assertFalse(floating.coversScreen());
    }

    @Test public void disjointFragmentsAreTiledButDoNotCoverTheScreen() {
        SplitGeometry geometry = new SplitGeometry(new Rect(0, 0, 1000, 400),
                Arrays.asList(new Rect(0, 0, 296, 400)),
                Arrays.asList(new Rect(304, 0, 500, 400), new Rect(700, 0, 1000, 400)), null, 1);
        assertTrue(geometry.isTiled());
        assertFalse(geometry.coversScreen());
    }

    @Test public void outOfOrderBridgeCompletesThreeFragmentCoverage() {
        SplitGeometry geometry = new SplitGeometry(new Rect(0, 0, 1000, 400),
                Arrays.asList(new Rect(0, 0, 296, 400)),
                Arrays.asList(new Rect(304, 0, 500, 400), new Rect(700, 0, 1000, 400),
                        new Rect(500, 0, 700, 400)), null, 1);
        SplitGeometry reordered = new SplitGeometry(new Rect(0, 0, 1000, 400),
                Arrays.asList(new Rect(0, 0, 296, 400)),
                Arrays.asList(new Rect(700, 0, 1000, 400), new Rect(500, 0, 700, 400),
                        new Rect(304, 0, 500, 400)), null, 1);
        assertTrue(geometry.coversScreen());
        assertEquals(30, geometry.percent());
        assertTrue(geometry.sameBounds(reordered));
    }

    @Test public void changedFragmentsWithTheSameOuterBoundsHaveNotSettled() {
        SplitGeometry before = new SplitGeometry(new Rect(0, 0, 1000, 400),
                Arrays.asList(new Rect(0, 0, 200, 400), new Rect(200, 0, 496, 400)),
                Arrays.asList(new Rect(504, 0, 1000, 400)), null, 1);
        SplitGeometry after = new SplitGeometry(new Rect(0, 0, 1000, 400),
                Arrays.asList(new Rect(0, 0, 240, 400), new Rect(240, 0, 496, 400)),
                Arrays.asList(new Rect(504, 0, 1000, 400)), null, 1);
        assertFalse(before.sameBounds(after));
    }

    @Test public void fragmentOuterUnionCannotHideMissingCrossAxisCoverage() {
        SplitGeometry geometry = new SplitGeometry(new Rect(0, 0, 1000, 400),
                Arrays.asList(new Rect(0, 0, 296, 400)),
                Arrays.asList(new Rect(304, 0, 700, 320), new Rect(700, 80, 1000, 400)), null, 1);
        assertTrue(geometry.isTiled());
        assertFalse(geometry.coversScreen());
    }

    @Test public void embeddedAppGroupKeepsItsPhysicalOrderWhenSidesAreReversed() {
        SplitGeometry geometry = new SplitGeometry(new Rect(0, 0, 1000, 400),
                Arrays.asList(new Rect(700, 0, 1000, 400), new Rect(504, 0, 700, 400)),
                Arrays.asList(new Rect(0, 0, 496, 400)), null, 1);
        assertTrue(geometry.coversScreen());
        assertFalse(geometry.firstIsLeft);
        assertEquals(new Rect(0, 0, 496, 400), geometry.first);
        assertEquals(50, geometry.percent());
    }
}
