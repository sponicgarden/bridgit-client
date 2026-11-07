package cc.bridgit.app.capture

import org.junit.Assert.*
import org.junit.Test

class CapturePresentationRulesTest {
 @Test fun interactiveCardsCannotDisableTheViewfinder() {
  for(via in listOf("card","portal","mcp_app")) assertTrue(CapturePresentationRules.requiresViewfinder(via,false))
 }
 @Test fun automaticMonitoringKeepsItsBackgroundPathAndExplicitApiCaptureCanOptIn() {
  assertFalse(CapturePresentationRules.requiresViewfinder("watch",false))
  assertFalse(CapturePresentationRules.requiresViewfinder("http",false))
  assertTrue(CapturePresentationRules.requiresViewfinder("http",true))
  assertTrue(CapturePresentationRules.interactiveFlow("button"))
  assertTrue(CapturePresentationRules.interactiveFlow("portal"))
  assertFalse(CapturePresentationRules.interactiveFlow("watch"))
  assertFalse(CapturePresentationRules.interactiveFlow("schedule"))
 }
}
