package com.gow3.android.game;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.InputDevice;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Runtime rumble snapshots -> active controller, falling back to phone haptics. */
final class VibrationBridge implements InputManager.InputDeviceListener {
    private final File state;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final InputManager input;
    private final Vibrator phone;
    private Vibrator vibrator;
    private VibratorManager motors;
    private int deviceId=-1;
    private boolean active;
    private String last="";
    private int playingAmplitude;
    private long playingUntil;

    VibrationBridge(Context context,File padState) {
        state=new File(padState.getPath()+".vibration");
        input=context.getSystemService(InputManager.class);
        phone=context.getSystemService(Vibrator.class);
        input.registerInputDeviceListener(this,main);
    }

    // CLOCK_MONOTONIC and uptimeMillis both exclude suspend time.
    static int[] command(String text,long now) {
        if (text.length()>96) return null;
        String[] fields=text.trim().split("\\s+");
        if (fields.length!=3) return null;
        try {
            long stamp=Long.parseLong(fields[0]);
            int low=Integer.parseInt(fields[1]),high=Integer.parseInt(fields[2]);
            if (stamp<0 || stamp>now || now-stamp>=1000 || low<0 || high<0 || low>65535 || high>65535) return null;
            int strength=Math.max(low,high);
            return new int[]{strength==0 ? 0 : Math.max(1,(strength*255+32767)/65535),(int)(1000-(now-stamp))};
        } catch (NumberFormatException invalid) { return null; }
    }

    private void cancel() {
        if (motors!=null) motors.cancel();
        else if (vibrator!=null) vibrator.cancel();
        motors=null; vibrator=null;
        playingAmplitude=0; playingUntil=0;
    }

    void device(InputDevice device) {
        if (device==null || (!device.supportsSource(InputDevice.SOURCE_GAMEPAD) &&
                !device.supportsSource(InputDevice.SOURCE_JOYSTICK))) return;
        if (deviceId!=device.getId()) { cancel(); deviceId=device.getId(); }
    }

    private void vibrate(int amplitude,int duration) {
        cancel();
        if (amplitude==0) return;
        InputDevice device=InputDevice.getDevice(deviceId);
        if (device==null) {
            for (int id:InputDevice.getDeviceIds()) {
                InputDevice candidate=InputDevice.getDevice(id);
                if (candidate!=null && candidate.supportsSource(InputDevice.SOURCE_GAMEPAD)) {
                    device=candidate; deviceId=id; break;
                }
            }
        }
        if (device!=null && Build.VERSION.SDK_INT>=31 && device.getVibratorManager().getVibratorIds().length>0) {
            motors=device.getVibratorManager();
            boolean amplitudeControl=true;
            for (int id:motors.getVibratorIds()) amplitudeControl &= motors.getVibrator(id).hasAmplitudeControl();
            // Android exposes no standard low/high motor mapping; apply the stronger channel to all motors.
            motors.vibrate(CombinedVibration.createParallel(VibrationEffect.createOneShot(duration,
                amplitudeControl ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE)));
        } else {
            vibrator=device==null ? null : device.getVibrator();
            if (vibrator==null || !vibrator.hasVibrator()) vibrator=phone;
            if (vibrator!=null && vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(duration,
                vibrator.hasAmplitudeControl() ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE));
        }
        playingAmplitude=amplitude; playingUntil=SystemClock.uptimeMillis()+duration;
    }

    private final Runnable poll=new Runnable() {
        @Override public void run() {
            if (!active) return;
            try {
                if (state.isFile() && state.length()<=96) {
                    String text=new String(Files.readAllBytes(state.toPath()),StandardCharsets.US_ASCII);
                    if (!text.equals(last)) {
                        last=text;
                        int[] value=command(text,SystemClock.uptimeMillis());
                        if (value==null) cancel();
                        else if (value[0]!=playingAmplitude || (value[0]>0 && playingUntil-SystemClock.uptimeMillis()<100))
                            vibrate(value[0],value[1]);
                    }
                } else cancel();
            } catch (Exception error) {
                cancel();
                android.util.Log.w("Gow3Vibration","Unable to apply runtime vibration",error);
            }
            main.postDelayed(this,16);
        }
    };

    void resume() {
        if (active) return;
        state.delete(); last=""; active=true; main.post(poll);
    }
    void pause() { active=false; main.removeCallbacks(poll); cancel(); }
    void stop() { main.post(() -> { cancel(); state.delete(); last=""; }); }
    void close() { pause(); input.unregisterInputDeviceListener(this); }
    @Override public void onInputDeviceAdded(int id) {}
    @Override public void onInputDeviceChanged(int id) { if (id==deviceId) cancel(); }
    @Override public void onInputDeviceRemoved(int id) { if (id==deviceId) { cancel(); deviceId=-1; } }
}
