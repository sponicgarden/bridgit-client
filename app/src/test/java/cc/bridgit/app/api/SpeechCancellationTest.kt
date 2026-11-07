package cc.bridgit.app.api

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.core.Connection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SpeechCancellationTest {
 @Test fun stopDuringSynthesisCannotEnqueueLateSpeech() = runCase(false)
 @Test fun appletVoiceKeepsActorAndCannotEnqueueAfterStop() = runCase(true)
 private fun runCase(cloud: Boolean) = runBlocking {
  val app=RuntimeEnvironment.getApplication() as BridgeApp
  app.settings.listenerEnabled=true;app.settings.setOn("speaker",true)
  val agent=app.agents.add("Speech cancellation test",Connection.APPLET).first
  app.agents.update(agent.id) { it.copy(caps=setOf("speaker")) }
  val started=CompletableDeferred<File>();val finish=CompletableDeferred<Unit>();val done=CompletableDeferred<Unit>()
  val queue=app.speaker
  queue.synthesizeSpeech={_,file -> file.writeBytes(byteArrayOf(1,2,3));started.complete(file);kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { finish.await();done.complete(Unit) };true }
  queue.synthesizeAppletSpeech={text,file,valid -> assertEquals(agent.id,cc.bridgit.app.outbound.Outbound.actor().agentId);assertTrue(valid());queue.synthesizeSpeech(text,file) }
  queue.speakText(agent.id,agent.name,"A test tip",false,allowCloud=cloud)
  val file=withTimeout(5000) { started.await() }
  queue.stop(agent.id);finish.complete(Unit);withTimeout(5000) { done.await() }
  withTimeout(5000) { while(file.exists()) delay(10) }
  assertNull(queue.current);assertTrue(queue.queued().isEmpty())
 }
 @Test fun cloudBudgetRefusalIsHandledAndTemporaryAudioIsDeleted() = runBlocking {
  val app=RuntimeEnvironment.getApplication() as BridgeApp
  app.settings.listenerEnabled=true;app.settings.setOn("speaker",true)
  val agent=app.agents.add("Budget speech test",Connection.APPLET).first
  app.agents.update(agent.id) { it.copy(caps=setOf("speaker")) }
  val started=CompletableDeferred<File>();val queue=app.speaker
  queue.synthesizeAppletSpeech={_,file,_ -> file.writeBytes(byteArrayOf(1));started.complete(file);throw cc.bridgit.app.judgment.Jev.Failure("applet_budget","Private failure detail") }
  queue.speakText(agent.id,agent.name,"Private speech text",false,allowCloud=true)
  val file=withTimeout(5000) { started.await() }
  withTimeout(5000) { while(file.exists()) delay(10) }
  val entries=app.log.query(cc.bridgit.app.log.LogQuery(channels=listOf("diagnostic"),q="applet_budget"))
  assertTrue(entries.isNotEmpty());assertFalse(entries.any { it.message?.contains("Private")==true })
  assertNull(queue.current);assertTrue(queue.queued().isEmpty())
 }

}
