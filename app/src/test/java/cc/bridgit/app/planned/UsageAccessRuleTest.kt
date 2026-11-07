package cc.bridgit.app.planned

import org.junit.Assert.*
import org.junit.Test

class UsageAccessRuleTest {
    @Test fun defaultModeUsesPackagePermission() {
        assertTrue(UsageAccessRule.allowed(3, true))
        assertFalse(UsageAccessRule.allowed(3, false))
    }
    @Test fun explicitDenialWinsOverPackagePermission() {
        assertFalse(UsageAccessRule.allowed(1, true))
        assertFalse(UsageAccessRule.allowed(2, true))
        assertTrue(UsageAccessRule.allowed(0, false))
    }
}
