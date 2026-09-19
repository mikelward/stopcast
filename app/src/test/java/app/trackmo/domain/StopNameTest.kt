package app.trackmo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StopNameTest {
    @Test
    fun `strips the Underground Station suffix`() {
        assertEquals("Charing Cross", cleanStopName("Charing Cross Underground Station"))
        assertEquals("Oxford Circus", cleanStopName("Oxford Circus Underground Station"))
    }

    @Test
    fun `strips DLR, Rail, and Overground suffixes`() {
        assertEquals("Canary Wharf", cleanStopName("Canary Wharf DLR Station"))
        assertEquals("London Bridge", cleanStopName("London Bridge Rail Station"))
        assertEquals("Highbury & Islington", cleanStopName("Highbury & Islington Overground Station"))
    }

    @Test
    fun `strips a bare Station suffix only after the specific ones`() {
        // "Euston Station" → "Euston" via the catch-all; "X Underground Station" loses the
        // whole phrase, not just "Station", because the specific suffix is tried first.
        assertEquals("Euston", cleanStopName("Euston Station"))
        assertEquals("Baker Street", cleanStopName("Baker Street Underground Station"))
    }

    @Test
    fun `leaves a name with no type suffix unchanged`() {
        // Most bus stops carry no "Station" suffix.
        assertEquals("Trafalgar Square (Stop A)", cleanStopName("Trafalgar Square (Stop A)"))
        assertEquals("Aldwych / Somerset House", cleanStopName("Aldwych / Somerset House"))
    }

    @Test
    fun `does not empty a name that is only the suffix`() {
        // Guarded so a stop literally named "Station" survives rather than becoming blank.
        assertEquals("Station", cleanStopName("Station"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("Victoria", cleanStopName("  Victoria Underground Station  "))
    }

    @Test
    fun `branchOf pulls the via trunk from towards`() {
        assertEquals("Charing Cross", branchOf("Battersea Power Station via Charing Cross"))
        assertEquals("Bank", branchOf("Edgware via Bank"))
    }

    @Test
    fun `branchOf is null without a via`() {
        assertNull(branchOf(null))
        assertNull(branchOf("Walthamstow Central"))
        // A bus-style comma tail is not a branch.
        assertNull(branchOf("Pimlico, Grosvenor Road"))
    }

    @Test
    fun `branchOf drops a comma tail after the via`() {
        assertEquals("Bank", branchOf("Morden via Bank, Kennington"))
    }

    @Test
    fun `abbreviateBranch shortens Cross and the compass words a board shortens`() {
        assertEquals("Charing X", abbreviateBranch("Charing Cross"))
        assertEquals("Kings X", abbreviateBranch("Kings Cross"))
        assertEquals("E. Ham", abbreviateBranch("East Ham"))
        assertEquals("W. Croydon", abbreviateBranch("West Croydon"))
        assertEquals("Walthamstow C.", abbreviateBranch("Walthamstow Central"))
        assertEquals("N. Greenwich", abbreviateBranch("North Greenwich"))
    }

    @Test
    fun `abbreviateBranch leaves a branch with nothing safe to shorten unchanged`() {
        assertEquals("Bank", abbreviateBranch("Bank"))
        assertEquals("Battersea", abbreviateBranch("Battersea"))
    }
}
