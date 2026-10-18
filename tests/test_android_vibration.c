#define _POSIX_C_SOURCE 200809L
#include <assert.h>
#include <stdlib.h>
#include "src/runtime_android_vibration.h"

int main(void) {
    char dir[]="/tmp/gow3-rumble-XXXXXX",pad[256],path[256];
    assert(mkdtemp(dir));
    snprintf(pad,sizeof(pad),"%s/android-pad.state",dir);
    snprintf(path,sizeof(path),"%s/android-pad.state.vibration",dir);
    assert(android_vibration_write(NULL,1,2)==-1);
    assert(android_vibration_write("/nonexistent/gow3/pad",1,2)==-1);
    assert(android_vibration_write(pad,65535,32768)==0);
    FILE *in=fopen(path,"r");
    unsigned long long stamp; unsigned low,high;
    assert(in && fscanf(in,"%llu %u %u",&stamp,&low,&high)==3);
    fclose(in);
    assert(stamp>0 && low==65535 && high==32768);
    assert(android_vibration_write(pad,0,0)==0);
    in=fopen(path,"r");
    assert(in && fscanf(in,"%llu %u %u",&stamp,&low,&high)==3);
    fclose(in);
    assert(low==0 && high==0);
    char too_long[4096]; memset(too_long,'x',sizeof(too_long)-1); too_long[4095]=0;
    assert(android_vibration_write(too_long,1,2)==-1);
    unlink(path); rmdir(dir);
    puts("Android vibration IPC checks passed");
}
