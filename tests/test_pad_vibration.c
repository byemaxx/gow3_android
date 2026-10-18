#include "src/runtime_pad.c"
#include <assert.h>

static int percent=100,rumble_calls;
static uint16_t last_low,last_high;
int gow3gpu_vibration_percent(void) { return percent; }
SDL_InitFlags SDL_WasInit(SDL_InitFlags flags) { return flags; }
bool SDL_InitSubSystem(SDL_InitFlags flags) { (void)flags; return true; }
bool SDL_GamepadConnected(SDL_Gamepad *pad) { return pad!=NULL; }
void SDL_CloseGamepad(SDL_Gamepad *pad) { (void)pad; }
SDL_JoystickID *SDL_GetGamepads(int *count) { *count=0; return NULL; }
SDL_Gamepad *SDL_OpenGamepad(SDL_JoystickID id) { (void)id; return NULL; }
const char *SDL_GetGamepadName(SDL_Gamepad *pad) { (void)pad; return "test"; }
void SDL_free(void *memory) { free(memory); }
bool SDL_RumbleGamepad(SDL_Gamepad *pad,uint16_t low,uint16_t high,uint32_t duration) {
    (void)pad; assert(duration==1000); ++rumble_calls; last_low=low; last_high=high; return true;
}

int main(void) {
    char dir[]="/tmp/gow3-pad-rumble-XXXXXX",path[256],state[256];
    assert(mkdtemp(dir));
    snprintf(state,sizeof(state),"%s/android-pad.state",dir);
    snprintf(path,sizeof(path),"%s/android-pad.state.vibration",dir);
    setenv("GOW3_ANDROID_INPUT","1",1); setenv("GOW3_PAD_FILE",state,1);
    opened=1;
    const uint8_t motors[2]={255,128};
    assert(pad_vibration(2,motors)==ERR_INVALID_HANDLE);
    assert(pad_vibration(PAD_HANDLE,NULL)==ERR_INVALID_ARG);
    percent=50;
    assert(pad_vibration(PAD_HANDLE,motors)==0 && rumble_calls==0);
    FILE *in=fopen(path,"r"); unsigned long long stamp; unsigned low,high;
    assert(in && fscanf(in,"%llu %u %u",&stamp,&low,&high)==3); fclose(in);
    assert(low==32767 && high==16448);
    percent=0;
    assert(pad_vibration(PAD_HANDLE,motors)==0);
    in=fopen(path,"r"); assert(in && fscanf(in,"%llu %u %u",&stamp,&low,&high)==3); fclose(in);
    assert(low==0 && high==0);
    percent=100;
    assert(pad_vibration(PAD_HANDLE,motors)==0);
    assert(pad_close(PAD_HANDLE)==0);
    in=fopen(path,"r"); assert(in && fscanf(in,"%llu %u %u",&stamp,&low,&high)==3); fclose(in);
    assert(low==0 && high==0 && pad_vibration(PAD_HANDLE,motors)==ERR_INVALID_HANDLE);
    opened=1; setenv("GOW3_PAD_FILE","/nonexistent/gow3/pad",1);
    assert(pad_vibration(PAD_HANDLE,motors)==-1);
    unsetenv("GOW3_ANDROID_INPUT"); sdl_ready=1; gamepad=(SDL_Gamepad *)1;
    assert(pad_vibration(PAD_HANDLE,motors)==0 && rumble_calls==1);
    assert(last_low==65535 && last_high==32896);
    unlink(path); rmdir(dir);
    puts("scePadSetVibration Android routing/scaling/stop and desktop fallback passed");
}
