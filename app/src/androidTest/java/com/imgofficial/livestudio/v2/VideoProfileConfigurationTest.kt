package com.imgofficial.livestudio.v2

import androidx.test.ext.junit.runners.AndroidJUnit4
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
    }
   }
   assertFalse(service.configureVideoProfile(540,30))
   assertFalse(service.configureVideoProfile(480,60))
   assertFalse(service.configureVideoProfile(720,24))
   assertFalse(service.configureVideoProfile(2160,60))
  }finally{ }
 }
}
