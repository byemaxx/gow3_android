#ifndef GOW3_ANDROID_VIBRATION_H
#define GOW3_ANDROID_VIBRATION_H
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* Reverse of android-pad.state: atomic snapshots cannot backlog old rumble commands. */
static int android_vibration_write(const char *pad_file, uint16_t low, uint16_t high) {
    char path[4096], temp[4096];
    if (!pad_file || !*pad_file || strlen(pad_file)+sizeof(".vibration.tmp")>sizeof(path)) return -1;
    snprintf(path,sizeof(path),"%s.vibration",pad_file);
    snprintf(temp,sizeof(temp),"%s.vibration.tmp",pad_file);
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC,&now)) return -1;
    FILE *out=fopen(temp,"w");
    if (!out) return -1;
    int written=fprintf(out,"%llu %u %u\n",
        (unsigned long long)now.tv_sec*1000u+(unsigned long long)now.tv_nsec/1000000u,low,high);
    int closed=fclose(out);
    if (written<0 || closed || rename(temp,path)) { unlink(temp); return -1; }
    return 0;
}
#endif
