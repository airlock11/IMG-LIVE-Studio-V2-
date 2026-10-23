package com.imgofficial.livestudio.v2

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** This test checks profile selection, not device camera or YouTube acceptance.
 * Real-device 1080p/60 hardware tests remain necessary. */
@RunWith(AndroidJUnit4::class)
class VideoProfileConfigurationTest {
 @Test fun acceptsExactlyFourRequestedEncodingProfiles(){
  val service=V2BroadcastService()
  try{
   for(resolution in listOf(720,1080)){
    for(fps in listOf(30,60)){
     assertTrue("Cannot select "+resolution+"p at "+fps+"fps",
      service.configureVideoProfile(resolution,fps))
     val initial=when {
      resolution==1080&&fps==60->9000
      resolution==1080->7000
      fps==60->5000
      else->4000
     }
     val floor=when {
      resolution==1080&&fps==60->6000
      resolution==1080->5000
      else->3000
     }
     assertEquals("Safe initial upload target for "+resolution+"p"+fps,initial,
      service.configuredVideoKbps())
     assertEquals("Maintain H.264 minimum for "+resolution+"p"+fps,floor,
      service.minimumVideoKbps())
     assertTrue("Display must label encoder target rather than internet speed",
      service.connectionSummary().contains("video target "+initial+" kbps"))
    }
   }
   assertFalse(service.configureVideoProfile(540,30))
   assertFalse(service.configureVideoProfile(480,60))
   assertFalse(service.configureVideoProfile(720,24))
   assertFalse(service.configureVideoProfile(2160,60))
  }finally{ }
 }
}
