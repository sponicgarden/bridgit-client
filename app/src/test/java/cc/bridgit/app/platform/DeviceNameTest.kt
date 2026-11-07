package cc.bridgit.app.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceNameTest {
    @Test fun ownersAgentControlNameWins() =
        assertEquals("Kitchen tab", DeviceName.pick("Kitchen tab", "A11 Blue Tab", "samsung", "SM-X238U"))

    @Test fun phonesOwnNameBeatsTheModelCode() =
        assertEquals("A11 Blue Tab", DeviceName.pick("", "A11 Blue Tab", "samsung", "SM-X238U"))

    @Test fun systemNameThatIsJustTheModelFallsBackToTheMarketingName() {
        assertEquals("Samsung Galaxy Tab A11+", DeviceName.pick(null, "SM-X238U", "samsung", "SM-X238U"))
        assertEquals("Samsung Galaxy Tab A9+", DeviceName.pick(null, " ", "samsung", "SM-X210"))
    }

    @Test fun unknownModelsUseMakerAndModel() {
        assertEquals("Google Pixel 10 Pro XL", DeviceName.pick(null, null, "Google", "Pixel 10 Pro XL"))
        assertEquals("Samsung SM-S928U", DeviceName.pick(null, null, "samsung", "SM-S928U"))
    }

    @Test fun controlCharactersAreDroppedAndLongNamesCut() {
        assertEquals("A11 Blue Tab", DeviceName.pick(null, "A11 Blue Tab\u0007", "samsung", "SM-X238U"))
        assertEquals(DeviceName.MAX, DeviceName.pick("x".repeat(200), null, "samsung", "SM-X238U").length)
    }
}
