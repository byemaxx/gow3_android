package ro.cobrabm.fexdroid;

import org.junit.Test;
import static org.junit.Assert.*;

public class VibrationBridgeTest {
    @Test public void scalesBothChannelsAndHonorsRemainingTime() {
        assertArrayEquals(new int[]{255,1000},VibrationBridge.command("1000 65535 0\n",1000));
        assertArrayEquals(new int[]{128,500},VibrationBridge.command("1000 0 32768\n",1500));
        assertArrayEquals(new int[]{1,1000},VibrationBridge.command("1000 1 0",1000));
        assertArrayEquals(new int[]{0,1000},VibrationBridge.command("1000 0 0",1000));
    }
    @Test public void rejectsExpiredFutureMalformedAndOutOfRangeCommands() {
        for (String text:new String[]{"1000 1 2","2001 1 2","-1 1 2","2000 -1 0",
                "2000 0 65536","2000 1 2 3","bad","999999999999999999999 1 2"})
            assertNull(text,VibrationBridge.command(text,2000));
    }
}
