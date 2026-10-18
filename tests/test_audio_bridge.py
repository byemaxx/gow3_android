"""Run the real AudioBridge loop against a paced fake Android sink (no device required)."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    'android/media/AudioAttributes.java': '''package android.media;
public class AudioAttributes {
 public static final int USAGE_GAME=1,CONTENT_TYPE_MUSIC=2;
 public static class Builder {
  public Builder setUsage(int n){return this;}
  public Builder setContentType(int n){return this;}
  public AudioAttributes build(){return new AudioAttributes();}
 }
}''',
    'android/media/AudioFormat.java': '''package android.media;
public class AudioFormat {
 public static final int CHANNEL_OUT_STEREO=1,ENCODING_PCM_16BIT=2;
 public static class Builder {
  public Builder setSampleRate(int n){return this;}
  public Builder setEncoding(int n){return this;}
  public Builder setChannelMask(int n){return this;}
  public AudioFormat build(){return new AudioFormat();}
 }
}''',
    'android/media/AudioTrack.java': '''package android.media;
public class AudioTrack {
 public static final int MODE_STREAM=1,PERFORMANCE_MODE_LOW_LATENCY=1,
  STATE_INITIALIZED=1,PLAYSTATE_PLAYING=3,WRITE_BLOCKING=0;
 public static volatile AudioTrack latest;
 public volatile int playState,bytes,mutedBytes,pauses;
 public volatile float volume=1;
 public volatile boolean released;
 public static int getMinBufferSize(int a,int b,int c){return 4096;}
 public int getState(){return STATE_INITIALIZED;}
 public int getPlayState(){return playState;}
 public void play(){playState=PLAYSTATE_PLAYING;}
 public void pause(){pauses++;playState=2;}
 public int setVolume(float value){volume=value;return 0;}
 public void stop(){playState=1;}
 public void release(){released=true;}
 public int write(byte[] b,int off,int n,int mode){
  if(playState!=PLAYSTATE_PLAYING)return 0;
  try{Thread.sleep(2);}catch(InterruptedException e){Thread.currentThread().interrupt();}
  bytes+=n;if(volume==0)mutedBytes+=n;return n;
 }
 public static class Builder {
  public Builder setAudioAttributes(AudioAttributes a){return this;}
  public Builder setAudioFormat(AudioFormat f){return this;}
  public Builder setBufferSizeInBytes(int n){return this;}
  public Builder setTransferMode(int n){return this;}
  public Builder setPerformanceMode(int n){return this;}
  public AudioTrack build(){return latest=new AudioTrack();}
 }
}''',
    'android/system/Os.java': '''package android.system;
import java.io.*;
public class Os {
 public static class Stat {public int st_mode;}
 public static Stat stat(String p){return new Stat();}
 public static void mkfifo(String p,int mode)throws IOException{new File(p).createNewFile();}
 public static int fcntlInt(FileDescriptor fd,int cmd,int value){return 0;}
}''',
    'android/system/OsConstants.java': '''package android.system;
public class OsConstants {public static boolean S_ISFIFO(int mode){return true;}}''',
    'android/util/Log.java': '''package android.util;
public class Log {
 public static int i(String tag,String text){return 0;}
 public static int d(String tag,String text){return 0;}
 public static int w(String tag,String text){return 0;}
}''',
    'com/gow3/android/game/AudioBridgeRegression.java': '''package com.gow3.android.game;
import android.media.AudioTrack;
import java.io.*;
import java.util.function.BooleanSupplier;
public class AudioBridgeRegression {
 static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static void await(BooleanSupplier condition,String message)throws Exception{
  long until=System.nanoTime()+2_000_000_000L;
  while(!condition.getAsBoolean() && System.nanoTime()<until)Thread.sleep(2);
  require(condition.getAsBoolean(),message);
 }
 public static void main(String[] args)throws Exception{
  File fifo=new File(args[0],"audio.fifo");
  try(RandomAccessFile file=new RandomAccessFile(fifo,"rw")){file.setLength(16*1024*1024);}
  AudioBridge bridge=new AudioBridge(fifo);
  bridge.start();
  try{
   require(bridge.awaitReady(2000),"bridge not ready");
   AudioTrack track=AudioTrack.latest;
   require(track.volume==1,"foreground startup must play");
   await(()->track.bytes>=8192,"foreground not consuming PCM");
   for(int cycle=0;cycle<3;cycle++){
    bridge.pause();int before=track.bytes;
    await(()->track.bytes>=before+16384,"paused activity backlogged audio");
    require(track.volume==0 && track.pauses==0,"background must mute, not pause device clock");
    // A system-induced stall must recover even while backgrounded.
    track.playState=2;
    await(()->track.getPlayState()==AudioTrack.PLAYSTATE_PLAYING,"muted stall did not recover");
    bridge.resume();require(track.volume==1,"resume remained silent");
   }
   bridge.stop();await(()->track.released,"stop did not release AudioTrack");
   require(!new File(fifo.getPath()+".ready").exists(),"stale ready marker");
  }finally{bridge.stop();}
  AudioBridge late=new AudioBridge(fifo);
  late.pause();late.start();
  try{
   require(late.awaitReady(2000),"muted startup not ready");
   AudioTrack track=AudioTrack.latest;
   require(track.volume==0,"background startup must stay muted");
   await(()->track.mutedBytes>=8192,"background startup must drain PCM");
   late.resume();require(track.volume==1,"late resume remained silent");
   late.stop();await(()->track.released,"muted startup did not release track");
  }finally{late.stop();}
  System.out.println("AudioBridge background drain, muted startup, repeated resume and stall recovery passed");
 }
}''',
}


def main():
    java_home = Path(os.environ['JAVA_HOME'])
    suffix = '.exe' if os.name == 'nt' else ''
    with tempfile.TemporaryDirectory(prefix='gow3-audio-test-') as folder:
        work = Path(folder)
        for name, content in SOURCES.items():
            target = work / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding='utf-8')
        production = ROOT / 'android-launcher/app/src/main/java/com/gow3/android/game/AudioBridge.java'
        subprocess.run([str(java_home/'bin'/('javac'+suffix)), '-d', str(work),
                        *[str(work/name) for name in SOURCES], str(production)], check=True)
        subprocess.run([str(java_home/'bin'/('java'+suffix)), '-cp', str(work),
                        'com.gow3.android.game.AudioBridgeRegression', str(work)], check=True)


if __name__ == '__main__':
    main()
