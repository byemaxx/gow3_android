package com.gow3.android.game;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.gow3.android.core.settings.FrameGenSettings;
import com.gow3.android.core.settings.GamePrefs;
import com.gow3.android.core.settings.Gow3Settings;
import com.gow3.android.Gow3App;

/** God of War III-only Android player. Keeps the game surface alive beneath its sliding drawer. */
public final class GameActivity extends Activity {
    /** The app's files (application id com.gow3.android.bionic) and its gow3port runtime tree.
     *  Rootfs programs are launched through the in-tree loader when their PT_INTERP slot cannot
     *  hold the app-private path. */
    private String FILES, ROOT, BASE, DATA;
    /** The game (e.g. CUSA01740, with eboot.bin) in shared storage, chosen by the player. */
    private String gameDir;
    /** An optional update folder (e.g. CUSA01740-patch): laid over the game at start. */
    private String updateDir;
    /** SharedPreferences("gow3") 的唯一读写口：键名与默认值都在 GamePrefs 一处。 */
    private GamePrefs prefs;
    private static final int REQUEST_FOLDER=1, REQUEST_STORAGE=2;
    private SurfaceHolder pendingHolder;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private FrameLayout screen;
    private SurfaceView surface;
    private View scrim, handle;
    private LinearLayout drawer;
    private LinearLayout drawerItems;
    private TextView status;
    private View resume;
    private LinearLayout gameSettingsItem, cheatsItem, graphicsItem, losslessItem, controllerItem, swapFaceItem, perfHudItem;
    private TouchControllerOverlay controllerOverlay;
    private PerfHudOverlay perfHud;
    private PadBridge pad;
    private VibrationBridge vibration;
    private AudioBridge audio;
    private volatile Process game;
    private volatile int shmid=-1;
    private volatile boolean destroyed;
    /** 报告 LSFG 运行时失败并自动回退后置位：刷新率要退回「无 LSFG」的目标，避免
     *  停在 LSFG 倍率对应的高刷新率上空跑（4.1 复评）。 */
    private volatile boolean lsfgFallbackOff;
    /** Bionic 运行时（gow3port/bionic/bin/gow3-probe）存在时用 NDK/Bionic 原生路径：不经
     *  帧走 GOW3_PRESENT_SOCKET（Gow3Present）。 */
    /** Bionic 下 app 侧 present socket（run-bionic.sh 的 GOW3_PRESENT_SOCKET 同一个）。 */
    private String presentSocket() { return FILES+"/gow3present.sock"; }
    private boolean drawerOpen, displayOnly, startingX, hadError, swipeConsumed;
    /** gow3.ini 的 display_stretch：开启时 Surface 铺满整屏（画面拉伸，不留 16:9 黑边）。 */
    private boolean displayStretch;
    /** 主页 LAUNCH 传入的单次启动踢动标记：消费一次后即失效（防 Surface 重建静默重启）。 */
    private boolean launchOnReady;
    private float touchX, touchY;
    private boolean edgeGesture, imeOpen, imePolling;
    private final AtomicBoolean imeAnswered=new AtomicBoolean();
    /** 文本框打开期间手柄的 Cross/Circle 要当作 OK/Cancel,需要拿到当前输入内容。 */
    private EditText imeField;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        FILES=getFilesDir().getAbsolutePath();
        ROOT=FILES+"/rootfs"; BASE=FILES+"/gow3port"; DATA=FILES+"/gow3data";
        // Context 级服务走 Gow3App 的手工 DI 容器（design D4）。
        prefs=((Gow3App)getApplication()).getContainer().getGamePrefs();
        gameDir=prefs.gameDir();
        updateDir=prefs.updateDir();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT>=28) {
            WindowManager.LayoutParams attrs=getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
        displayOnly="x".equals(getIntent().getStringExtra("action"));
        // 主页 LAUNCH 的单次启动踢动标记：读到后立刻从 intent 里移除，保证它不跨
        // Activity 重建（旋转/翻盖）存活——重建后 surfaceCreated 不得静默重启游戏。
        launchOnReady=!displayOnly && "launch".equals(getIntent().getStringExtra("action"));
        if (launchOnReady) getIntent().removeExtra("action");
        pad=new PadBridge(new File(BASE,"android-pad.state"),
            prefs.swapSquareTriangle());
        vibration=new VibrationBridge(this,new File(BASE,"android-pad.state"));
        audio=new AudioBridge(new File(BASE,"audio.fifo"));
        buildScreen();
        immersive();
    }

    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t;
    }
    private FrameLayout.LayoutParams frame(int width,int height,int gravity) {
        return new FrameLayout.LayoutParams(width,height,gravity);
    }
    private void fitSurface(int width,int height) {
        if (width<=0 || height<=0) return;
        // display_stretch=1：跳过 16:9 适配，Surface 铺满整屏（缓冲被拉伸，消除左右黑边）。
        if (displayStretch) {
            FrameLayout.LayoutParams full=(FrameLayout.LayoutParams)surface.getLayoutParams();
            if (full.width==-1 && full.height==-1) return;
            full.width=-1; full.height=-1;
            surface.post(() -> surface.setLayoutParams(full));
            return;
        }
        int w=width, h=height;
        if ((long)w*9>(long)h*16) w=h*16/9; else h=w*9/16;
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)surface.getLayoutParams();
        if (p.width==w && p.height==h) return;
        p.width=w; p.height=h;
        // Not during the layout pass that reported the size.
        surface.post(() -> surface.setLayoutParams(p));
    }

    private void buildScreen() {
        screen=new FrameLayout(this); screen.setBackgroundColor(Color.BLACK);
        surface=new SurfaceView(this);
        screen.addView(surface,frame(-1,-1,Gravity.CENTER));
        // The game is 16:9: the largest 16:9 area of the screen, black bars around it (a foldable's
        // near-square inner screen, a tall cover screen). Refitted when folding or rotating.
        screen.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> fitSurface(r-l,b-t));
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                // Surface 重建（熄屏、翻盖、旋转）只恢复画面连接，从不启动游戏：启动只发生在
                // 用户动作的 startX 流程里。shmid 在游戏进程结束时复位，所以游戏退出后再熄屏
                // 也不会在这里把整个游戏静默拉起来。
                if (shmid>=0) attach(h, false);
                else {
                    // 不在这里启动游戏：surface 回调只记下 holder，等用户动作（LAUNCH/权限/选目录
                    // -> startPending）发起 startX。否则游戏退出后一次熄屏/翻盖就会把整个游戏
                    // 静默重启。MainActivity owns the first-run game-folder check；走到这说明
                    // launcher 被绕过或所选目录已不在。
                    pendingHolder=h;
                    if (!hasGame()) error("Game folder is unavailable. Return to the launcher and choose it again.");
                    else if (launchOnReady) {
                        // 主页 LAUNCH 带来的单次踢动：目录可用即开始启动流程（runtime
                        // 解包 → bridge → 游戏进程）。只踢一次，之后回到「等用户动作」。
                        launchOnReady=false;
                        startPending();
                    }
                }
            }
            @Override public void surfaceChanged(SurfaceHolder h,int format,int w,int height) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                // Do not close the frame connection here. A surface goes away for transient reasons
                // too (screen off, folding, a resize); Gow3Present's own SurfaceHolder.Callback
                // already handles this by handing the surface back with setSurface(null) and keeps
                // the socket, so the game keeps running and the next surface resumes showing. The
                // game treats a closed connection as "end of game" and exits immediately, so
                // stopping here turned every surface loss into a crash back to the launcher. The
                // legacy X11 bridge is gone from this build, so there is nothing left to stop.
            }
        });
        surface.setOnTouchListener((v,e) -> {
            // Touch reaches the game through the on-screen control overlay, not the retired X11
            // input bridge.
            return true;
        });
        status=text("Starting God of War III…",18,0xffc6ad76); status.setGravity(Gravity.CENTER);
        screen.addView(status,frame(-1,-1,Gravity.CENTER));
        status.setClickable(false);

        controllerOverlay=new TouchControllerOverlay(this,pad,prefs);
        controllerOverlay.setVisibility(controllerEnabled() ? View.VISIBLE : View.GONE);
        screen.addView(controllerOverlay,frame(-1,-1,Gravity.CENTER));

        // 性能 HUD（android-perf-hud-overlay）：叠在 controllerOverlay 之上、scrim/drawer 之下。
        // 抽屉打开时 scrim 全屏盖住 HUD，触摸到不了 HUD（spec「抽屉打开时不可穿透误触」）。
        perfHud=new PerfHudOverlay(this,prefs);
        perfHud.setActive(perfHudEnabled());
        screen.addView(perfHud,frame(-1,-1,Gravity.CENTER));

        handle=new View(this);
        GradientDrawable grip=new GradientDrawable(); grip.setColor(0x80c6ad76); grip.setCornerRadius(dp(4));
        handle.setBackground(grip); handle.setContentDescription("Open game menu");
        screen.addView(handle,frame(dp(4),dp(44),Gravity.LEFT|Gravity.CENTER_VERTICAL));
        handle.setOnClickListener(v -> openDrawer());

        scrim=new View(this); scrim.setBackgroundColor(0x99000000);
        scrim.setVisibility(View.GONE); scrim.setOnClickListener(v -> closeDrawer());
        screen.addView(scrim,frame(-1,-1,Gravity.FILL));

        drawer=new LinearLayout(this); drawer.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panel=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xff1b1d24,0xff121318});
        drawer.setBackground(panel);
        drawer.setElevation(dp(16)); drawer.setVisibility(View.GONE);
        drawerItems=new LinearLayout(this); drawerItems.setOrientation(LinearLayout.VERTICAL);
        drawerItems.setPadding(dp(14),dp(20),dp(14),dp(16));
        android.widget.ScrollView drawerScroll=new android.widget.ScrollView(this);
        drawerScroll.setFillViewport(false);
        drawerScroll.addView(drawerItems);
        // A thin gold edge on the game side.
        LinearLayout body=new LinearLayout(this);
        body.addView(drawerScroll,new LinearLayout.LayoutParams(0,-1,1));
        View edge=new View(this); edge.setBackgroundColor(0x55c6ad76);
        body.addView(edge,new LinearLayout.LayoutParams(dp(1),-1));
        drawer.addView(body,new LinearLayout.LayoutParams(-1,0,1));

        TextView hint=text("Swipe left or press A to return",12,0xff8e9097);
        hint.setPadding(dp(10),dp(2),0,dp(6));
        drawerItems.addView(hint,new LinearLayout.LayoutParams(-1,-2));

        section("Game");
        resume=menuItem("ic_play","Resume game",null,() -> closeDrawer());
        /* Game settings 与 Cheats 两个入口已隐藏（对应 SettingsDialog.showGame()/showCheats() 也已
         * 注释掉）：它们写的 ini 键在本仓库的启动链路里没有消费方，作弊更是根本没装 hook——Android
         * 走 FEXCore JIT，probe.c 的 install_cheats() 被 `#if defined(GUEST_CPU_NATIVE)` 编译掉了。
         * 留着入口只会让玩家以为改了有用。接通后把下面两段取消注释即可。
        gameSettingsItem=menuItem("ic_settings","Game settings","",() -> {
            closeDrawer();
            new SettingsDialog(this,new File(DATA,"gow3.ini"),this::restartGame).showGame();
        });
        cheatsItem=menuItem("ic_cheats","Cheats","",() -> {
            closeDrawer();
            new SettingsDialog(this,new File(DATA,"gow3.ini"),this::restartGame).showCheats();
        });
         */
        menuItem("ic_refresh","Restart game","Unsaved progress is lost",() -> new AlertDialog.Builder(this)
            .setTitle("Restart God of War III?").setMessage("Any unsaved progress will be lost.")
            .setNegativeButton("Cancel",null).setPositiveButton("Restart",(dialog,which) -> restartGame()).show());

        section("Graphics");
        // Graphics 入口只保留运行时真正消费的那几项：upscaler / preset / sharpen / sharpness /
        // fps_limit。show_fps（引擎 FPS 叠加层）已退役——帧率显示由本 Activity 的性能 HUD
        // （PerfHudOverlay）提供，历史残留的 show_fps=1 由 Gow3Data.prepare 归零。原来的 CPU
        // 核心（thor_cpus）、特效（effect_*）、模型细节
        // （model_lod）在本仓库启动链路没有消费方，已在 SettingsDialog.show() 里注释掉。
        // fps_limit 的摘要取值域与 SettingsDialog.FPS_VALUES 和运行时 FPS_LIMITS
        // （30/60/120/240/0）对齐。
        graphicsItem=menuItem("ic_tune","Graphics settings","",() -> {
            closeDrawer();
            new SettingsDialog(this,new File(DATA,"gow3.ini"),this::restartGame).show();
        });
        // Tuning only. Supplying Lossless.dll is the launcher Home page's job;
        // the drawer's row reports the multiplier, never where the DLL came from. LSFG 是唯一帧生成
        // 路径，FSR 3.1 帧生成未接通，不要在这里再加 FSR 帧生成入口。
        // 帧生成调参入口（change add-native-frame-generation）：未导入 Lossless.dll 时禁用
        // （灰显 + 指引文案），供给入口在设置页常规分类。DLL 查找与 run-bionic 一致：
        // 游戏目录同级 → 应用私有 gow3port/。
        File lsfgDll=new File(BASE,"Lossless.dll");
        try {
            String gd=new GamePrefs(this).gameDir();
            if (gd!=null) {
                File beside=new File(new File(gd).getParentFile(),"Lossless.dll");
                if (beside.isFile()) lsfgDll=beside;
            }
        } catch (Exception ignored) { }
        boolean dllReady=lsfgDll.isFile();
        losslessItem=menuItem("ic_frames","Lossless Scaling",
            dllReady ? "" : getString(com.gow3.android.R.string.frame_gen_drawer_need_dll), () -> {
            if (!dllReady) return;
            closeDrawer();
            new SettingsDialog(this,new File(DATA,"gow3.ini"),null)
                .showLossless(() -> { Process p=game; return p!=null && p.isAlive(); }, this::pickRefreshRate);
        });
        if (!dllReady) losslessItem.setAlpha(0.4f);
        // 性能 HUD 开关行：launcher 侧悬浮层，即时生效（不重启游戏进程、不重建 Surface），
        // 交互模式与「On-screen controller」一致——点击切换、写 prefs、收起抽屉。
        perfHudItem=menuItem("ic_tune","Performance HUD","",() -> {
            boolean on=!perfHudEnabled();
            prefs.setPerfHudEnabled(on);
            perfHud.setActive(on);
            closeDrawer();
        });

        section("Controls");
        controllerItem=menuItem("ic_gamepad","On-screen controller","",() -> {
            boolean enabled=!controllerEnabled();
            prefs.setControllerOverlayEnabled(enabled);
            controllerOverlay.release();
            closeDrawer();
        });
        // 部分物理手柄按印刷标签上报 X/Y 轴,方块/三角与常见手柄相反;这一行即时切换交换,
        // 只影响物理手柄,不影响触屏虚拟按键。
        swapFaceItem=menuItem("ic_gamepad","Swap Square/Triangle (some gamepads)","",() -> {
            boolean on=!swapFaceEnabled();
            prefs.setSwapSquareTriangle(on);
            if (pad!=null) pad.setSwapFaceButtons(on);
            closeDrawer();
        });
        menuItem("ic_edit","Edit controller layout","Drag and resize the buttons",() -> {
            prefs.setControllerOverlayEnabled(true);
            closeDrawer();
            controllerOverlay.edit(null);
        });
        menuItem("ic_restore","Reset controller layout",null,() -> {
            controllerOverlay.resetLayout();
            closeDrawer();
        });

        menuItem("ic_exit","Quit game",null,() -> new AlertDialog.Builder(this)
            .setTitle("Quit God of War III?").setMessage("Any unsaved progress will be lost.")
            .setNegativeButton("Cancel",null).setPositiveButton("Quit",(dialog,which) -> finish()).show());

        // The version, pinned under the scrolling menu.
        String version="";
        try { version=" "+getPackageManager().getPackageInfo(getPackageName(),0).versionName; }
        catch (Exception ignored) {}
        TextView about=text("God of War III for Android"+version,12,0xff8e9097);
        about.setPadding(dp(24),dp(8),dp(14),dp(14));
        drawer.addView(about,new LinearLayout.LayoutParams(-1,-2));
        screen.addView(drawer,frame(dp(320),-1,Gravity.LEFT));
        setContentView(screen);
    }

    private void restartGame() {
        closeDrawer(); status.setText("Restarting God of War III…"); status.setVisibility(View.VISIBLE);
        worker.execute(() -> { stopGame(); startGame(); });
    }

    /** A small gold heading above a group of menu rows. */
    private void section(String name) {
        TextView t=text(name.toUpperCase(java.util.Locale.ROOT),11,0xffc6ad76);
        t.setLetterSpacing(0.15f); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(10),dp(16),0,dp(6));
        drawerItems.addView(t,new LinearLayout.LayoutParams(-1,-2));
    }

    /** A menu row: icon, title and an optional second line (null: none; "" filled by refreshDrawer).
     *  Focusable for the gamepad, with a gold highlight when focused or pressed. */
    private LinearLayout menuItem(String icon,String title,String detail,Runnable action) {
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(10),dp(8),dp(10),dp(8));
        row.setFocusable(true); row.setClickable(true); row.setTag(action); row.setOnClickListener(v -> ((Runnable)row.getTag()).run());
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        GradientDrawable lit=new GradientDrawable(); lit.setColor(0x33c6ad76); lit.setCornerRadius(dp(10));
        lit.setStroke(dp(1),0x99c6ad76);
        GradientDrawable pressed=new GradientDrawable(); pressed.setColor(0x44c6ad76); pressed.setCornerRadius(dp(10));
        states.addState(new int[]{android.R.attr.state_pressed},pressed);
        states.addState(new int[]{android.R.attr.state_focused},lit);
        states.addState(new int[]{},new android.graphics.drawable.ColorDrawable(0));
        row.setBackground(states);

        android.widget.ImageView image=new android.widget.ImageView(this);
        int id=getResources().getIdentifier(icon,"drawable",getPackageName());
        if (id!=0) image.setImageResource(id);
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(24),dp(24)); ip.rightMargin=dp(14);
        row.addView(image,ip);

        LinearLayout texts=new LinearLayout(this); texts.setOrientation(LinearLayout.VERTICAL);
        TextView name=text(title,15,0xffe8dfc9);
        texts.addView(name);
        if (detail!=null) {
            TextView second=text(detail,12,0xff8e9097); second.setTag("detail");
            texts.addView(second);
        }
        row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(2);
        row.setMinimumHeight(dp(48));
        drawerItems.addView(row,lp); return row;
    }
    private static void setDetail(LinearLayout row,String value) {
        TextView detail=row==null ? null : (TextView)row.findViewWithTag("detail");
        if (detail!=null) detail.setText(value);
    }
    /** 抽屉里的一行不可用时：不可点击、不可获焦（手柄/键盘导航会跳过它），并整体变灰。
     *  action 存在 row 的 tag 里，监听器保持挂着——setClickable(false) 会让点击不再分发，
     *  这样重新启用时不必再重挂一次监听器。 */
    private static void setRowEnabled(LinearLayout row,boolean on) {
        if (row==null) return;
        row.setClickable(on);
        row.setFocusable(on);
        row.setAlpha(on ? 1.0f : 0.4f);
    }
    /** The rows' second lines: the current settings, read when the drawer opens. */
    private void refreshDrawer() {
        /* gameSettingsItem / cheatsItem 的摘要随上面两个入口一起注释掉了（接回来时一起放开）。
        java.util.List<String> game=new java.util.ArrayList<>();
        if (!"0".equals(setting("skip_network_choice"))) game.add("Offline start");
        if ("1".equals(setting("skip_intro"))) game.add("no intros");
        if ("1".equals(setting("debug_camera"))) game.add("free camera");
        if ("1".equals(setting("debug_menu"))) game.add("debug menu");
        setDetail(gameSettingsItem,game.isEmpty() ? "Online/offline screen shown" : String.join(" · ",game));
        java.util.List<String> cheats=new java.util.ArrayList<>();
        if ("1".equals(setting("cheat_health"))) cheats.add("infinite health");
        if ("1".equals(setting("cheat_item"))) cheats.add("infinite items");
        setDetail(cheatsItem,cheats.isEmpty() ? "Off" : String.join(" · ",cheats));
         */
        String upscaler="off".equals(Gow3Settings.read(configIni(), "upscaler")) ? "No upscaling" : "FSR 3.1";
        String limit=Gow3Settings.read(configIni(), "fps_limit");
        // 取值域与 SettingsDialog.FPS_VALUES 一致：只有 30/60/120/240 会按原值显示，0 是"不限制"，
        // 其余（键缺失、旧版遗留的 40/45）运行时 ParseFpsLimit 都当 0，所以回退显示 30。
        setDetail(graphicsItem,upscaler+" · "+("0".equals(limit) ? "no FPS limit"
            : (limit==null || !limit.matches("30|60|120|240") ? "30" : limit)+" FPS limit"));
        String lsfg=Gow3Settings.read(configIni(), "lsfg_multiplier");
        boolean lsfgOn="2".equals(lsfg) || "3".equals(lsfg) || "4".equals(lsfg);
        // 没导入 Lossless.dll 就开不了插帧：lsfg-vk 只加载玩家自带的 DLL，run-gow3.sh 的
        //  preflight 找不到它时会静默不开 layer（只剩 30 FPS），玩家看不出是自己漏了导入。
        //  所以直接把这一行置灰不可点，并说明去 Home 页导入，而不是让它"看着能开却不生效"。
        boolean dllPresent=hasFrameGenDll();
        setRowEnabled(losslessItem,dllPresent);
        setDetail(losslessItem,!dllPresent ? "Import Lossless.dll on the Home page first"
            : lsfgOn ? lsfg+"× frames · flow "
            +(Gow3Settings.read(configIni(), "lsfg_flow_scale")==null ? "1.0" : Gow3Settings.read(configIni(), "lsfg_flow_scale")) : "Off");
        setDetail(controllerItem,controllerEnabled() ? "On" : "Off");
        setDetail(swapFaceItem,swapFaceEnabled() ? "On" : "Off");
        setDetail(perfHudItem,perfHudEnabled() ? "On" : "Off");
    }

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen=true; controllerOverlay.release(); pad.reset();
        refreshDrawer();
        controllerOverlay.setVisibility(View.GONE);
        handle.setVisibility(View.GONE);
        scrim.animate().cancel(); drawer.animate().cancel();
        scrim.setAlpha(0); scrim.setVisibility(View.VISIBLE); scrim.animate().alpha(1).setDuration(200).start();
        drawer.setTranslationX(-dp(320)); drawer.setVisibility(View.VISIBLE);
        drawer.animate().translationX(0).setDuration(220).start(); resume.requestFocus();
    }
    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen=false; pad.reset();
        controllerOverlay.setVisibility(controllerEnabled() ? View.VISIBLE : View.GONE);
        scrim.animate().cancel(); drawer.animate().cancel();
        scrim.animate().alpha(0).setDuration(180).withEndAction(() -> scrim.setVisibility(View.GONE)).start();
        drawer.animate().translationX(-dp(320)).setDuration(200)
            .withEndAction(() -> { drawer.setVisibility(View.GONE); handle.setVisibility(View.VISIBLE); surface.requestFocus(); }).start();
        immersive();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (Build.VERSION.SDK_INT>=30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c=getWindow().getInsetsController();
            if (c!=null) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            }
        }
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive(); else if (pad!=null) pad.reset();
    }
    @Override public void onBackPressed() { if (drawerOpen) closeDrawer(); else openDrawer(); }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked()==MotionEvent.ACTION_DOWN) {
            swipeConsumed=false;
            touchX=e.getX(); touchY=e.getY();
            edgeGesture=!drawerOpen && touchX<dp(28);
        }
        if (swipeConsumed) return true;
        if (e.getActionMasked()==MotionEvent.ACTION_MOVE) {
            float dx=e.getX()-touchX, dy=e.getY()-touchY;
            if (edgeGesture && dx>dp(40) && Math.abs(dy)<dp(90)) {
                openDrawer(); edgeGesture=false; swipeConsumed=true; return true;
            }
            if (drawerOpen && dx< -dp(70) && Math.abs(dy)<dp(90)) {
                MotionEvent cancel=MotionEvent.obtain(e); cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel); cancel.recycle();
                closeDrawer(); swipeConsumed=true; return true;
            }
        }
        if (edgeGesture) {
            if (e.getActionMasked()==MotionEvent.ACTION_UP) { edgeGesture=false; openDrawer(); }
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code=e.getKeyCode();
        if (code==KeyEvent.KEYCODE_BACK || code==KeyEvent.KEYCODE_BUTTON_MODE) {
            if (e.getAction()==KeyEvent.ACTION_UP) onBackPressed(); return true;
        }
        if (drawerOpen) {
            if (code==KeyEvent.KEYCODE_BUTTON_B || e.getScanCode()==305) {
                if (e.getAction()==KeyEvent.ACTION_UP) closeDrawer(); return true;
            }
            if (code==KeyEvent.KEYCODE_BUTTON_A || e.getScanCode()==304) {
                if (e.getAction()==KeyEvent.ACTION_UP && getCurrentFocus()!=null) getCurrentFocus().performClick();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        // 文本框开着时手柄要当作 OK/Cancel,必须在 pad.key 之前:它对 cross/circle 返回 true,
        // 放到后面就永远轮不到这里。按下确认名字的那个键也不该同时进游戏。
        if (imeGamepad(e)) return true;
        vibration.device(e.getDevice());
        if (pad.key(e)) return true;
        return super.dispatchKeyEvent(e);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (drawerOpen && e.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) return true;
        vibration.device(e.getDevice());
        return pad.motion(e) || super.dispatchGenericMotionEvent(e);
    }
    private boolean controllerEnabled() {
        return prefs.controllerOverlayEnabled();
    }
    private boolean swapFaceEnabled() {
        return prefs.swapSquareTriangle();
    }
    private boolean perfHudEnabled() {
        return prefs.perfHudEnabled();
    }
    @Override protected void onPause() {
        if (vibration!=null) vibration.pause();
        if (controllerOverlay!=null) controllerOverlay.release();
        if (pad!=null) pad.reset();
        if (audio!=null) audio.pause();
        super.onPause();
    }
    @Override protected void onResume() { super.onResume(); if (audio!=null) audio.resume(); if (vibration!=null) vibration.resume(); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Deterministic UI checks over ADB, using the same handlers as the actual controls.
        String action=intent.getStringExtra("ui_action");
        if ("open-menu".equals(action)) openDrawer();
        if ("close-menu".equals(action)) closeDrawer();
        // singleTask：主页 LAUNCH 复用已有实例。带 action=launch 的新 intent 表示「用户要（重）
        // 进游戏」：消费标记并走 startPending —— surface 仍在则直接重启启动流程，surface 已随
        // 退到后台销毁则由 surfaceCreated 的 launchOnReady 分支接力。绝不叠加第二个实例，避免
        // 新旧两套 bridge 竞争同一个 audio.fifo.ready（旧实例清理会删掉新会话的标记，
        // run-bionic 误报 exit 12）。
        if ("launch".equals(intent.getStringExtra("action"))) {
            intent.removeExtra("action");
            if (!displayOnly) {
                launchOnReady=true;
                startPending();
            }
        }
    }

    private Process process(String... args) throws java.io.IOException {
        ProcessBuilder b=new ProcessBuilder(args).redirectErrorStream(true);
        Map<String,String> env=b.environment(); env.clear();
        // Bionic: no rootfs, so the environment is the platform's - toybox paths, the Bionic
        // libraries, and the present socket.
            env.put("PATH","/system/bin");
            env.put("HOME",FILES+"/home/gow3port"); env.put("LANG","C.UTF-8");
            env.put("TMPDIR",FILES+"/tmp"); env.put("XDG_RUNTIME_DIR",FILES+"/tmp");
            env.put("FXD_FILES",FILES); env.put("GOW3_ANDROID_INPUT","1");
            env.put("LD_LIBRARY_PATH",BASE+"/bionic/lib");
            env.put("GOW3_PRESENT_SOCKET",presentSocket());
            env.put("GOW3_DATA_DIR",DATA);
            env.put("GOW3_CONFIG",DATA+"/gow3.ini");
            env.put("GOW3_USER_DIR",DATA+"/user");
            env.put("GOW3_LOG_DIR",DATA+"/logs");
        if (gameDir!=null) env.put("GOW3_GAME_DIR",gameDir);
        if (updateDir!=null) env.put("GOW3_PATCH_DIR",updateDir);
        return b.start();
    }
    private void startX(SurfaceHolder holder) {
        try {
            boolean installed=RuntimeInstaller.ensure(this, new File(FILES), message -> main.post(() -> status.setText(message)));
            android.util.Log.i("God of War III","runtime installed="+installed);
            if (audio!=null) audio.start();
            if (audio==null || !audio.awaitReady(5000))
                throw new java.io.IOException("Android audio bridge did not become ready; see logcat for details.");
            startXbionic(holder);
        } catch (Exception ex) { error(ex.toString()); }
    }
    /** Bionic 运行时的显示路径：不启动 fxshmd/Xvfb，直接用 Gow3Present 把帧送到 Surface。 */
    private void startXbionic(SurfaceHolder holder) {
        try {
            if (!new File(BASE,"bionic/bin/gow3-probe").canExecute() ||
                    !new File(BASE,"run-bionic.sh").isFile())
                throw new java.io.IOException("The Bionic runtime is missing. Install the runtime bundle.");
            new File(FILES,"home/gow3port").mkdirs();
            // present socket 由 Gow3Present 监听（run-bionic.sh 的 GOW3_PRESENT_SOCKET 指向它）。
            main.post(() -> attach(holder, true));
        } catch (Exception ex) { error(ex.toString()); }
    }
    /** @param launch true 仅当这是用户动作触发的 startX 流程；surface 重建传 false，只重连显示。 */
    private void attach(SurfaceHolder holder, boolean launch) {
        if (destroyed || !holder.getSurface().isValid()) return;
        // Frames reach the Surface through the present socket and Gow3Present (AHardwareBuffer).
        // display_stretch=1 时把画面拉伸铺满 SurfaceView（配置页显示选项），其余值都按保持宽高比处理。
        String result=Gow3Present.start(holder, presentSocket(), "1".equals(Gow3Settings.read(configIni(), "display_stretch")));
        android.util.Log.i("God of War III",result);
        if (!result.startsWith("bridge running:")) { error(result); return; }
        shmid=1; // present 已连接
        startingX=false; // 启动流程走完，之后的 surface 重建都走纯重连
        if (launch && !displayOnly && game==null) worker.execute(() -> startGame());
        pollFrames();
        if (!imePolling) { imePolling=true; pollIme(); }
    }
    // ---- Game folder: the extracted retail game in shared storage ----
    /** Known retail folder names; the selected folder is still validated by eboot.bin. */
    private static final String[] SERIALS={"CUSA01740","CUSA00900","CUSA00207","CUSA00208","CUSA00299",
        "CUSA01363","CUSA03179","CUSA03014","CUSA03023"};
    private static boolean isGame(File dir) { return dir!=null && new File(dir,"eboot.bin").isFile(); }
    private boolean hasGame() {
        if (Build.VERSION.SDK_INT>=23 && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                !=android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        return gameDir!=null && isGame(new File(gameDir));
    }
    /** Explains, asks for storage access when needed, then opens the folder picker. */
    private void askGameFolder(boolean change) {
        if (Build.VERSION.SDK_INT>=23 && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                !=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE},REQUEST_STORAGE);
            return;
        }
        status.setText(change ? "Choose the God of War III game folder." :
            "Choose your God of War III game folder (e.g. CUSA01740, the folder with eboot.bin).");
        status.setVisibility(View.VISIBLE);
        new AlertDialog.Builder(this).setTitle("God of War III game folder")
            .setMessage("Select the extracted game folder (e.g. CUSA01740 or CUSA00900, which contains eboot.bin) or the folder "
                +"that holds it, in your phone's storage."+(gameDir!=null ? "\n\nCurrent: "+gameDir : ""))
            .setPositiveButton("Choose folder",(d,w) -> {
                Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                try { startActivityForResult(pick,REQUEST_FOLDER); }
                catch (Exception ex) { error("No folder picker: "+ex); }
            })
            .setNegativeButton(change ? "Cancel" : "Quit",(d,w) -> { if (!change) finish(); })
            .setCancelable(change).show();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if (code!=REQUEST_STORAGE) return;
        if (results.length>0 && results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (hasGame()) startPending(); else askGameFolder(false);
        } else error("God of War III needs storage access to read the game folder.");
    }
    @Override protected void onActivityResult(int code,int result,Intent data) {
        super.onActivityResult(code,result,data);
        if (code!=REQUEST_FOLDER) return;
        if (result!=RESULT_OK || data==null || data.getData()==null) { if (!hasGame()) askGameFolder(false); return; }
        File dir=folderOf(data.getData());
        if (dir!=null && !isGame(dir))
            for (String serial : SERIALS) if (isGame(new File(dir,serial))) { dir=new File(dir,serial); break; }
        if (!isGame(dir)) {
            new AlertDialog.Builder(this).setTitle("Not a God of War III folder")
                .setMessage((dir==null ? "That location" : dir.getPath())+" has no eboot.bin.")
                .setPositiveButton("Choose again",(d,w) -> askGameFolder(gameDir!=null)).show();
            return;
        }
        boolean changed=gameDir!=null && !gameDir.equals(dir.getPath());
        gameDir=dir.getPath();
        prefs.setGameDir(gameDir);
        status.setText("Starting God of War III…");
        if (changed && game!=null) restartGame(); else startPending();
    }
    /** A storage folder of the picker (tree document "primary:Path" or "XXXX-XXXX:Path") as a path. */
    private static File folderOf(android.net.Uri tree) {
        try {
            String id=android.provider.DocumentsContract.getTreeDocumentId(tree);
            int colon=id.indexOf(':');
            String volume=colon<0 ? id : id.substring(0,colon), path=colon<0 ? "" : id.substring(colon+1);
            File base="primary".equalsIgnoreCase(volume) ? android.os.Environment.getExternalStorageDirectory()
                                                         : new File("/storage/"+volume);
            return path.isEmpty() ? base : new File(base,path);
        } catch (Exception ex) { return null; }
    }
    private void startPending() {
        SurfaceHolder h=pendingHolder!=null ? pendingHolder : surface.getHolder();
        pendingHolder=null;
        if (h.getSurface()!=null && h.getSurface().isValid() && !startingX && shmid<0) {
            startingX=true; worker.execute(() -> startX(h));
        }
    }

    private void pollFrames() {
        if (destroyed) return;
        if (!hadError && (Gow3Present.framesIfLoaded()>0))
            status.setVisibility(View.GONE);
        // run-gow3.sh 在 LSFG 运行时失败、自动回退到普通 Vulkan 时会写这个标记：这时刷新率要退回
        // 「无 LSFG」的目标，否则会停在 LSFG 倍率对应的高刷上空跑（4.1 复评）。
        if (!lsfgFallbackOff && new File(BASE,"lsfg-fallback.marker").isFile()) {
            lsfgFallbackOff=true;
            pickRefreshRate();
        }
        main.postDelayed(this::pollFrames,1000);
    }

    /** The game's text dialog (the character name, GOW3_IME_FILE in run-gow3.sh): shown here with the
     *  touch keyboard. The request holds the maximum length, the title and the current text. */
    private void pollIme() {
        if (destroyed) return;
        File request=new File(BASE,"ime.request");
        if (!imeOpen && request.isFile()) {
            try (BufferedReader r=new BufferedReader(new InputStreamReader(new java.io.FileInputStream(request),"UTF-8"))) {
                int max=Integer.parseInt(r.readLine().trim());
                String title=r.readLine(), initial=r.readLine();
                request.delete();
                showIme(Math.max(1,max),title==null ? "" : title,initial==null ? "" : initial);
            } catch (Exception ex) { android.util.Log.e("God of War III","text request: "+ex); request.delete(); }
        }
        main.postDelayed(this::pollIme,250);
    }
    private void showIme(int max,String title,String initial) {
        imeOpen=true; imeAnswered.set(false); pad.reset(); closeDrawer();
        EditText field=new EditText(this);
        field.setSingleLine(true); field.setText(initial); field.setSelection(field.getText().length());
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        FrameLayout box=new FrameLayout(this); box.setPadding(dp(20),dp(8),dp(20),0); box.addView(field);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title.isEmpty() ? "Enter text" : title)
            .setView(box)
            .setPositiveButton("OK",(d,w) -> answerIme(true,field.getText().toString()))
            .setNegativeButton("Cancel",(d,w) -> answerIme(false,""))
            .setOnCancelListener(d -> answerIme(false,"")).create();
        field.setOnEditorActionListener((v,action,event) -> {
            if (action!=EditorInfo.IME_ACTION_DONE) return false;
            dialog.dismiss(); answerIme(true,field.getText().toString()); return true;
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.setOnDismissListener(d -> { imeOpen=false; imeField=null; immersive(); surface.requestFocus(); });
        dialog.show(); field.requestFocus();
        imeField=field;
    }
    /**
     * 文本框开着时,屏幕手柄的 Cross 当 OK、Circle 当 Cancel:AlertDialog 不会因为
     * android-pad.state 变化而自己点按钮,所以在这里翻译一次。只在 ACTION_UP 触发,
     * answerIme 里的 compareAndSet 再挡一次重复提交。框已关闭(imeOpen=false)时不处理,
     * 否则关闭瞬间残留的一次 key-up 会去动下一个界面。
     */
    private boolean imeGamepad(KeyEvent e) {
        if (!imeOpen || e.getAction()!=KeyEvent.ACTION_UP) return false;
        int code=e.getKeyCode(), scan=e.getScanCode();
        boolean cross=code==KeyEvent.KEYCODE_BUTTON_A || scan==304;
        boolean circle=code==KeyEvent.KEYCODE_BUTTON_B || scan==305;
        if (!cross && !circle) return false;
        EditText field=imeField;
        answerIme(cross, cross && field!=null ? field.getText().toString() : "");
        imeOpen=false; imeField=null;
        return true;
    }
    private void answerIme(boolean ok,String value) {
        if (!imeAnswered.compareAndSet(false,true)) return;
        File temporary=new File(BASE,"ime.request.result.tmp"), result=new File(BASE,"ime.request.result");
        try (java.io.Writer w=new java.io.OutputStreamWriter(new java.io.FileOutputStream(temporary),"UTF-8")) {
            w.write((ok ? "ok" : "cancel")+"\n"+value.replace('\n',' ')+"\n");
        } catch (Exception ex) { android.util.Log.e("God of War III","text answer: "+ex); return; }
        if (!temporary.renameTo(result)) android.util.Log.e("God of War III","text answer: rename failed");
    }
    /** launcher 的 gow3.ini（游戏、run-bionic.sh、游戏内对话框读的同一份文件）。 */
    private File configIni() { return new File(DATA,"gow3.ini"); }
    /** Frame generation (Lossless Scaling only) presents 2-4 frames per game frame. Lossless
     *  Scaling presents them back to back and FIFO spaces them, evenly only when the display runs at
     *  FPS limit x frames: that mode when the display has it (30x2: 60 Hz, 30x4, 30x3, 60x2: 120 Hz),
     *  else the fastest one (at the system's 60 Hz generated frames were dropped). Without frame
     *  generation, the system's choice. FSR 3.1 frame generation is not wired on this tree
     *  (shadps4 has no GOW3_FRAME_GEN consumer), so the multiplier is LSFG-only. */
    /** LSFG 是否**真的**会在本次启动启用——与 run-gow3.sh 的 preflight 用同一套规则：倍率 2-4、
     *  运行时的 layer（manifest + liblsfg-vk.so）都在、玩家的 Lossless.dll 在两处查找位置之一。
     *  刷新率必须依此判断，而不是只看 gow3.ini 的 lsfg_multiplier（4.2 复评）。 */
    private boolean lsfgLaunchable() {
        String lsfg=Gow3Settings.read(configIni(), "lsfg_multiplier");
        if (!"2".equals(lsfg) && !"3".equals(lsfg) && !"4".equals(lsfg)) return false;
        if (!new File(BASE,"arm64/lsfg/VkLayer_LS_frame_generation.json").isFile()) return false;
        if (!new File(BASE,"arm64/lsfg/liblsfg-vk.so").isFile()) return false;
        return hasFrameGenDll();
    }
    /** 玩家是否已经导入 Lossless.dll——只是给菜单项/刷新率用的只读闸门，不导入、不删除、
     *  也不持有 DLL 状态（那些都在 Home 页的 LosslessDllManager 里）。查找顺序与运行时一致：
     *  游戏目录旁的同名副本优先，其次应用私有的 gow3port 副本（LosslessDllManager.appCopy()
     *  写入的位置），和 run-gow3.sh 的 preflight 是同一套规则。这里不查第三个位置，因为
     *  run-gow3.sh 也不查。 */
    private boolean hasFrameGenDll() {
        File parent=gameDir!=null ? new File(gameDir).getParentFile() : null;
        if (parent!=null && new File(parent,"Lossless.dll").isFile()) return true;
        return new File(DATA,"Lossless.dll").isFile();
    }
    private void pickRefreshRate() {
        String lsfg=Gow3Settings.read(configIni(), "lsfg_multiplier");
        boolean lsfgOn=lsfgLaunchable() && !lsfgFallbackOff;
        int frames=lsfgOn ? Integer.parseInt(lsfg) : 1;
        String limit=Gow3Settings.read(configIni(), "fps_limit"); // 同 SettingsDialog.FPS_VALUES / 运行时 FPS_LIMITS
        final float target=frames*("30".equals(limit) || "60".equals(limit) || "120".equals(limit)
            || "240".equals(limit) ? Integer.parseInt(limit) : "0".equals(limit) ? 1000 : 30);
        main.post(() -> {
            if (destroyed) return;
            android.view.Display display=Build.VERSION.SDK_INT>=30 ? getDisplay() : getWindowManager().getDefaultDisplay();
            android.view.Display.Mode current=display.getMode();
            int id=0, fastest=0; float best=0;
            if (frames>1) for (android.view.Display.Mode mode:display.getSupportedModes()) {
                if (mode.getPhysicalWidth()!=current.getPhysicalWidth() || mode.getPhysicalHeight()!=current.getPhysicalHeight()) continue;
                if (Math.abs(mode.getRefreshRate()-target)<1) id=mode.getModeId();
                if (mode.getRefreshRate()>best) { best=mode.getRefreshRate(); fastest=mode.getModeId(); }
            }
            if (id==0) id=fastest;
            WindowManager.LayoutParams attrs=getWindow().getAttributes();
            if (attrs.preferredDisplayModeId!=id) { attrs.preferredDisplayModeId=id; getWindow().setAttributes(attrs); }
        });
    }
    /**
     * The Bionic runtime needs the game's derived inputs before it starts: the patch blob, the
     * AppContent profile and launch.env. The glibc build produced these with the Python scripts in
     * a rootfs or on a host; here libgow3prepare compiles them from the installed game. Returns
     * false and reports when the game cannot be prepared, so a stale run never starts.
     */
    private boolean prepareBionicGame() {
        try {
            File game=new File(gameDir);
            File update=updateDir!=null && !updateDir.isEmpty() ? new File(updateDir) : null;
            com.gow3.android.data.PatchCatalog catalog=
                    com.gow3.android.data.PatchCatalog.Companion.load(game, update, getAssets());
            java.util.Map<String,String> ini=Gow3Settings.read(configIni());
            com.gow3.android.data.Gow3Prepare.Result result=
                    com.gow3.android.data.Gow3Prepare.INSTANCE.prepare(this,game,update,catalog,ini);
            android.util.Log.i("God of War III",
                    "bionic prepare: "+result.getReason()+"; applied="+result.getApplied()
                            +"; writes="+result.getWriteCount()+"; dmem="+result.getDmemMb()
                            +"; vblank="+result.getVblankHz());
            if (!result.getOk()) {
                error("Game preparation failed ("+result.getReason()+"): "+result.getMessage());
                return false;
            }
            return true;
        } catch (com.gow3.android.data.PatchCatalogException ex) {
            error("This game's patch set is not available: "+ex.getProblem());
            return false;
        } catch (java.io.IOException ex) {
            android.util.Log.e("God of War III","bionic prepare: cannot read the patch catalogue",ex);
            error("Game preparation failed: "+ex);
            return false;
        } catch (Exception ex) {
            android.util.Log.e("God of War III","bionic prepare failed",ex);
            error("Game preparation failed: "+ex);
            return false;
        }
    }
    private void startGame() {
        if (destroyed) return;
        hadError=false;
        // display_stretch 每次启动从 gow3.ini 读取（launcher 的「拉伸铺满屏幕」开关）。
        displayStretch="1".equals(Gow3Settings.read(configIni(), "display_stretch"));
        // 帧生成 FIFO 前提联动（change add-native-frame-generation）：enabled 且 vsync≠1
        // 时写 vsync=1；状态文件由 run-bionic.sh 从 ini 播种（env 传路径）。
        FrameGenSettings.ensureVsyncForFrameGen(configIni());
        screen.post(() -> fitSurface(screen.getWidth(), screen.getHeight()));
        // 清掉上一轮的回退标记，并重置「LSFG 已被回退关闭」状态；刷新率按当前 launchability 选。
        lsfgFallbackOff=false;
        new File(BASE,"lsfg-fallback.marker").delete();
        pickRefreshRate();
        new File(BASE,"ime.request").delete(); new File(BASE,"ime.request.result").delete();
        if (!prepareBionicGame()) return;
        // 上一轮日志保留一代：写新的 android-game.log 前把旧的轮转为 android-game.prev.log，
        // 使崩溃日志在重启后不丢，并随日志导出一起打包。轮转失败时忽略，照常写新日志。
        File logDir=new File(DATA,"logs");
        File gameLog=new File(logDir,"android-game.log"), previous=new File(logDir,"android-game.prev.log");
        if (gameLog.isFile()) {
            if (previous.exists()) previous.delete();
            if (!gameLog.renameTo(previous))
                android.util.Log.w("God of War III","log rotation: could not rename android-game.log to android-game.prev.log");
        }
        try {
            // /system/bin/sh pulls run-bionic.sh directly (toybox): no rootfs loader, no X11.
            final Process launched=process("/system/bin/sh",BASE+"/run-bionic.sh","game");
            game=launched;
            Thread log=new Thread(() -> {
                try (BufferedReader r=new BufferedReader(new InputStreamReader(launched.getInputStream()));
                     PrintWriter out=new PrintWriter(new File(DATA,"logs/android-game.log"))) {
                    String line; while ((line=r.readLine())!=null) { out.println(line); out.flush(); }
                    int code=launched.waitFor();
                    if (game==launched) {
                        game=null;
                        vibration.stop();
                        shmid=-1; // present 的对端（游戏进程）没了：surface 重建不得再算"已连接"
                        Gow3Present.stop();
                        // run-gow3.sh exits 4 when Vulkan/Turnip cannot initialize: the driver
                        // failure must not be reported as validated texture integrity.
                        if (code==4) error("Graphics initialization failed (Vulkan/Turnip). See logs/android-game.log.");
                        else error("Game stopped ("+code+"). Open the menu to restart.");
                    }
                } catch (Exception ex) {
                    // A restart (settings, cheats) kills this process on purpose: its reader is
                    // interrupted then, after the next game started. Only the current game's errors show.
                    if (!destroyed && game==launched) error(ex.toString());
                }
            },"God of War III-log"); log.setDaemon(true); log.start();
        } catch (Exception ex) { error(ex.toString()); }
    }
    private void error(String message) {
        hadError=true;
        android.util.Log.e("God of War III",message);
        main.post(() -> { if (!destroyed) { status.setText(message); status.setVisibility(View.VISIBLE); } });
    }
    private void stopGame() {
        if (vibration!=null) vibration.stop();
        Process p=game; game=null;
        if (p!=null) {
            p.destroyForcibly();
            try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        shmid=-1; // 同上：游戏进程没了，surface 重建只能走完整的用户启动流程
        Gow3Present.stop();
    }
    @Override protected void onDestroy() {
        if (vibration!=null) vibration.close();
        destroyed=true; pad.reset(); audio.stop(); main.removeCallbacksAndMessages(null);
        // 性能 HUD 先于其余清理停止采样线程（spec「退出游戏停止采样」），对照 controllerOverlay.release() 先例。
        if (perfHud!=null) perfHud.release();
        worker.shutdownNow();
        // The teardown used to run here on the main thread, which is what turned every quit into an
        // ANR. Process.waitFor() blocks until the game is gone, so the teardown runs on its own
        // thread: the activity goes away at once and the game is reaped behind it.
        final Process p=game; game=null;
        new Thread(() -> {
            try {
                Gow3Present.stop();
                if (p!=null) {
                    p.destroyForcibly();
                    try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            } catch (RuntimeException ignored) {
                // Teardown is best effort: the activity is already going away.
            }
        },"gow3-teardown").start();
        super.onDestroy();
    }
}
