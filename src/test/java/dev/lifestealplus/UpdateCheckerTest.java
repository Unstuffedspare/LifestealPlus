package dev.lifestealplus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Which release counts as newer than the one running. */
class UpdateCheckerTest {

    @Test
    void versionsCompareNumberByNumber() {
        assertTrue(UpdateChecker.isNewer("v0.2.0", "0.1.0"));
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.9"), "10 is more than 9, not less");
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"));
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1.0"));
        assertFalse(UpdateChecker.isNewer("v0.1.0", "0.2.0"));
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0"), "the same version written differently");
    }

    @Test
    void aReleaseBeatsItsOwnPreview() {
        assertTrue(UpdateChecker.isNewer("1.0.0", "1.0.0-beta"));
        assertFalse(UpdateChecker.isNewer("1.0.0-beta", "1.0.0"));
    }
}
