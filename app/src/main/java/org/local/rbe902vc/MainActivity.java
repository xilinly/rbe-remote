package org.local.rbe902vc;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Foreground BLE HOGP remote for the user's ChangHong TV (hisi551_SEC, Android 5.1.1).
 *
 * The TV's Realtek stack pairs remotes through /etc/bluetooth/rtkbt_autopair_config.xml on the
 * plugin side, so the decisive data is the advertisement of the real "CH BT RC" remote while its
 * pairing combo (settings + volume-down) is held. This build can capture that advertisement,
 * replay it, and send the combo key usages.
 */
public final class MainActivity extends Activity {
    private static final String REMOTE_NAME = "CH BT RC";
    private static final String LOG_TAG = "RBE_SCAN";
    private static final int PERMISSION_REQUEST = 1;
    private static final int SCAN_PERMISSION_REQUEST = 2;
    private static final long SCAN_MILLIS = 60000L;    private static final int SCAN_CHUNKS = 10;
    private static final long SCAN_GAP_MILLIS = 800L;

    private static final UUID HID_SERVICE = uuid(0x1812);
    private static final UUID DEVICE_INFO_SERVICE = uuid(0x180A);
    private static final UUID BATTERY_SERVICE = uuid(0x180F);
    private static final UUID HID_INFO = uuid(0x2A4A);
    private static final UUID HID_CONTROL = uuid(0x2A4C);
    private static final UUID HID_REPORT_MAP = uuid(0x2A4B);
    private static final UUID HID_REPORT = uuid(0x2A4D);
    private static final UUID HID_PROTOCOL_MODE = uuid(0x2A4E);
    private static final UUID PNP_ID = uuid(0x2A50);
    private static final UUID MANUFACTURER = uuid(0x2A29);
    private static final UUID MODEL = uuid(0x2A24);
    private static final UUID BATTERY_LEVEL = uuid(0x2A19);
    private static final UUID REPORT_REFERENCE = uuid(0x2908);
    private static final UUID CLIENT_CONFIG = uuid(0x2902);

    // Exact 0x2A4B report map saved by the TV for its paired "CH BT RC" remote.
    // No Bluetooth address or pairing key is copied into this app.
    private static final byte[] REPORT_MAP = hex(
            "05010906a1018501050719e029e715002501750195088102950175088101"
          + "95057501050819012905910295017503910195067508150025ff05071900"
          + "29ff8100c0050c0901a101850319002a9c021500269c02950175108100c0"
          + "050c0901a101850895147508150025ff8100c0"
          + "050c0901a10185fc95147508150026ff008100c0"
          + "050c0901a10185f895147508150026ff008100c0"
          + "050c0901a10185f995017508150026ff008100c0"
          // System Control collection (report 9): Generic Desktop usage 0x81 "System Power Down" as a
          // 1-bit variable field. The TV's kernel maps HID_UP_GENDESK 0x81 -> KEY_POWER, which Android
          // turns into KEYCODE_POWER(26); the device keylayout has every POWER line commented out, so
          // this collection — not the keyboard report — is the only way to reach the power key.
          + "05010980a1018509198129811500250175019501810295078101c0");

    // HID keyboard usages observed from the real remote and confirmed against CH_BT_RC.kl
    // (identity mapping: HID usage == Android scancode for this device).
    private static final int UP = 0xBC, DOWN = 0xBD, LEFT = 0xBE, RIGHT = 0xBF;
    private static final int OK = 0xC6, BACK = 0xC7, VOL_UP = 0xDC, VOL_DOWN = 0xDD;
    // CH_BT_RC.kl line "key 178 CHANGHONGIR_SETTINGS"; measured on the TV on 2026-11-10: sending
    // usage 0xB2 arrives as scancode 178 -> keycode 4125 and opens the system settings panel.
    private static final int SETTINGS_KEY = 0xB2;

    // Same identity block in CH_BT_RC.kl names the keys still missing from the app. Measured on the
    // TV on 2026-11-10: 183 "SOURCE" -> keycode 2001 opens the 节目源 (signal source) panel, so that
    // is the real 换源 key; 213 CHANGHONGIR_INPUT -> keycode 4102 only gets as far as the app that
    // owns the screen and does nothing on the launcher, kept as a secondary "输入" key.
    private static final int SOURCE_KEY = 0xB7, INPUT_KEY = 0xD5;
    private static final int HOME_KEY = 0xBA, SHORTCUT_KEY = 0xEB, MOVIE_KEY = 0xB4;
    private static final int HOME_FALLBACK = 0x3E;

    // 电源：本机没有红外发射器，只能测“关机/待机”，开机要走实体遥控器的红外键。
    // 这台电视给该 HID 设备用的 /system/usr/keylayout/Vendor_0416_Product_0300.kl 里
    // "key 116 POWER" 与 "key 152 POWER" 都是注释状态，键盘报表发扫描码 0x74 不会出 keycode；
    // 所以电源走 Consumer 报表（report 3）的 Power 用法 0x30，由内核 hid-input 直接映射成
    // KEYCODE_POWER（26），绕开 keylayout。扫描码版本保留为备用键，便于对照测试。
    private static final int POWER_CONSUMER = 0x30;
    private static final int POWER_SCANCODE = 0x74;

    // 同一份恒等映射里其余可用的键：187 MENU、176/177 CHANNEL_UP/DOWN、204..212/214 数字键 1..9/0。
    private static final int MENU_KEY = 0xBB, CH_UP = 0xB0, CH_DOWN = 0xB1;
    private static final int[] DIGITS = {0xCC, 0xCD, 0xCE, 0xCF, 0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD6};

    // WiFi 备用通道：电视上一直跑着一个 busybox httpd（/data/local/webremote，端口 8080），
    // 它的 cgi-bin/power 用 `input keyevent 26` 真正关机——这台机型把 KEYCODE_POWER 映射成整机断电，
    // 所以 BLE 电源无论走不走得通，这条 HTTP 通道是确定可用的按键注入点。
    private static final String TV_DEFAULT_HOST = "192.168.3.17";
    private static final int TV_PORT = 8080;
    private static final String TV_TOKEN = "ch551";

    // From /etc/bluetooth/rtkbt_autopair_config.xml on the TV: the autopair plugin matches an
    // advertisement whose manufacturer specific data begins with these eight bytes.
    private static final int RTK_COMPANY_ID = 0x000D;
    private static final byte[] RTK_COMPANY_DATA = hex("383802000001");

    /**
     * Advertisement + scan response of the real remote (00:E0:29:EC:F2:32), captured 2026-11-10
     * while it was held in pairing mode (设置+音量减). Used when no capture file is available yet.
     */
    private static final String REMOTE_ADV_FALLBACK =
            "0201050303121809ff0d003838020000010909434820425420524312ff0d00ffff52424635303056435632303237";

    private final Handler main = new Handler(Looper.getMainLooper());

    /** Set while the activity exists so the adb debug receiver can push usages into the HID report. */
    static MainActivity current;
    private final List<BluetoothGattService> services = new ArrayList<>();
    private final List<String> captures = new ArrayList<>();
    private final Set<String> captureKeys = new HashSet<>();
    private TextView status;
    private TextView detail;
    private EditText custom;
    private EditText tvHost;
    private android.content.SharedPreferences prefs;
    private BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private BluetoothGattServer server;
    private BluetoothGattCharacteristic keyboardReport;
    /** Report 3 (Consumer control, 2 bytes) — used for the power key. */
    private BluetoothGattCharacteristic consumerReport;
    /** Report 9 (System Control, 1 byte, usage 0x81 = System Power Down). */
    private BluetoothGattCharacteristic systemReport;
    private BluetoothDevice connected;
    private String formerName;
    private boolean notificationsEnabled;
    private boolean advertising;
    private boolean scanning;
    private boolean scanSession;
    private int scanChunk;
    private int scanPackets;
    private int scanRetries;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!scanSession) return;
            showDetail("第 " + Math.min(scanChunk + 1, SCAN_CHUNKS) + "/" + SCAN_CHUNKS + " 段：已收到 "
                    + scanPackets + " 包、" + seenDevices.size() + " 个设备；相关 "
                    + captures.size() + " 种" + (captureFile == null ? "" : "；文件 " + captureFile.getName()));
            main.postDelayed(this, 1000);
        }
    };
    private final Set<String> seenDevices = new HashSet<>();
    private int nextService;

    private static UUID uuid(int shortId) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", shortId));
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static BluetoothGattCharacteristic readOnly(UUID id, byte[] value) {
        BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(id,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        c.setValue(value);
        return c;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        current = this;
        prefs = getSharedPreferences("rbe", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root);

        status = new TextView(this);
        status.setTextSize(15);
        status.setText("BLE 遥控器未启动。点下面的“连接电视（克隆广播）”。");
        root.addView(status);

        detail = new TextView(this);
        detail.setTextSize(11);
        detail.setText("捕获 0 条广播；最近一条：无");
        root.addView(detail);

        // ------------------------------------------------ 遥控器面板（按实体遥控器布局）
        root.addView(header("遥控器"));
        root.addView(rowEven(button("电源", v -> sendPower()),
                button("设置", v -> sendKeys(SETTINGS_KEY))));

        root.addView(rowEven(button("菜单", v -> sendKeys(MENU_KEY)),
                repeatButton("↑", UP),
                button("对码", v -> sendKeys(SETTINGS_KEY, VOL_DOWN))));
        root.addView(rowEven(repeatButton("←", LEFT), button("确定", v -> sendKeys(OK)),
                repeatButton("→", RIGHT)));
        root.addView(rowEven(button("返回", v -> sendKeys(BACK)), repeatButton("↓", DOWN),
                button("主页", v -> sendKeys(HOME_KEY))));

        root.addView(rowEven(repeatButton("音量－", VOL_DOWN), repeatButton("音量＋", VOL_UP),
                repeatButton("频道－", CH_DOWN), repeatButton("频道＋", CH_UP)));

        root.addView(rowEven(button("换源", v -> sendKeys(SOURCE_KEY)),
                button("快捷", v -> sendKeys(SHORTCUT_KEY)),
                button("观影", v -> sendKeys(MOVIE_KEY))));

        root.addView(rowEven(digitButton(1), digitButton(2), digitButton(3), digitButton(4),
                digitButton(5)));
        root.addView(rowEven(digitButton(6), digitButton(7), digitButton(8), digitButton(9),
                digitButton(0)));

        // ------------------------------------------------ 连接与调试
        root.addView(header("连接"));
        root.addView(rowEven(button("连接电视（克隆广播）", v -> requestStart(AdvMode.CLONE)),
                button("停止", v -> { stopScan(); stopRemote(); })));

        // ------------------------------------------------ 网络（WiFi 备用通道）
        root.addView(header("网络（电视上的 webremote HTTP 服务，电源走这条）"));
        tvHost = new EditText(this);
        tvHost.setHint("电视 IP（默认 " + TV_DEFAULT_HOST + "）");
        tvHost.setText(prefs.getString("tvHost", TV_DEFAULT_HOST));
        tvHost.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        root.addView(tvHost);
        root.addView(rowEven(button("关机（WiFi）", v -> tvPower("off")),
                button("休眠（WiFi）", v -> tvPower("sleep"))));
        root.addView(rowEven(button("唤醒（WiFi）", v -> tvPower("wake")),
                button("状态（WiFi）", v -> tvCall("/cgi-bin/status?p=" + TV_TOKEN, null))));

        root.addView(header("调试"));
        root.addView(rowEven(button("广播 HID", v -> requestStart(AdvMode.HID)),
                button("广播 RTK", v -> requestStart(AdvMode.RTK)),
                button("广播克隆", v -> requestStart(AdvMode.CLONE))));
        root.addView(rowEven(button("扫描实体遥控器（120 秒）", v -> requestScan()),
                button("复制捕获", v -> copyCaptures())));
        root.addView(rowEven(button("输入源 0xD5", v -> sendKeys(INPUT_KEY)),
                button("主页备用 0x3E", v -> sendKeys(HOME_FALLBACK))));
        root.addView(rowEven(button("电源备用 0x74", v -> sendPowerScancode()),
                button("电源备用 C0x30", v -> sendPowerConsumer())));

        custom = new EditText(this);
        custom.setHint("自定义 usage（十六进制，可逗号分隔，如 D5 或 B2,DD）");
        custom.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        root.addView(custom);
        root.addView(rowEven(button("发送自定义 usage", v -> sendCustom())));

        TextView note = new TextView(this);
        note.setTextSize(11);
        note.setText("对码：电视端蓝牙配对界面打开后，实体遥控器是同时按住“设置”和“音量减”。"
                + "连上后手机自动停止广播（等于实体遥控器松手），断开后自动恢复。"
                + "实测：设置 0xB2→178→4125 打开系统设置；换源 0xB7→183→2001 弹出节目源；"
                + "主页 0xBA→186→4124；快捷 0xEB→235→4160；观影 0xB4→180→4148。"
                + "输入源 0xD5→213→4102 只在个别界面有效，作备用。"
                + "电源：BLE 走报表 9 的 System Power Down；WiFi 那条走电视上的 webremote，"
                + "用 input keyevent 26 真的断电。本机没有红外，开机仍然必须用实体遥控器。"
                + "保持本界面在前台；退出会停止广播并恢复手机蓝牙名称。");
        root.addView(note);
        setContentView(scroll);
    }

    private TextView header(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13);
        t.setPadding(0, 18, 0, 6);
        return t;
    }

    private Button digitButton(int digit) {
        int usage = DIGITS[digit == 0 ? 9 : digit - 1];
        return button(String.valueOf(digit), v -> sendKeys(usage));
    }

    private Button button(String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(click);
        return b;
    }

    private LinearLayout row(Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        for (Button button : buttons) row.addView(button);
        return row;
    }

    /** Horizontal row whose buttons split the width evenly; the remote panel uses only this. */
    private LinearLayout rowEven(Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        for (Button button : buttons) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            params.setMargins(3, 3, 3, 3);
            row.addView(button, params);
        }
        return row;
    }

    private void show(String message) { main.post(() -> status.setText(message)); }

    private void showDetail(String message) { main.post(() -> detail.setText(message)); }

    // ------------------------------------------------------------ WiFi 备用通道

    private String tvHostValue() {
        String value = tvHost == null ? "" : tvHost.getText().toString().trim();
        if (value.isEmpty()) return TV_DEFAULT_HOST;
        return value.replace("http://", "").replace("/", "");
    }

    private void tvPower(String action) {
        prefs.edit().putString("tvHost", tvHostValue()).apply();
        String hint = "off".equals(action)
                ? "已请求关机（KEYCODE_POWER 26 在这台机型上是整机断电，电视会立刻掉网）"
                : "已请求 " + action;
        tvCall("/cgi-bin/power?p=" + TV_TOKEN + "&a=" + action, hint);
    }

    /**
     * GET 电视上 busybox httpd 的某个 CGI。必须在新线程里跑：API 30 起主线程不允许网络访问。
     * result 原样显示（cgi-bin/status 会回 {"ok":true,"act":"...","up":"..."}）。
     */
    private void tvCall(String path, String hint) {
        final String host = tvHostValue();
        final String url = "http://" + host + ":" + TV_PORT + path;
        show("请求 " + url);
        new Thread(() -> {
            String body;
            try {
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(5000);
                c.setRequestMethod("GET");
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder value = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) value.append(line);
                    body = value.toString();
                }
                c.disconnect();
            } catch (Exception e) {
                body = null;
                Log.i(LOG_TAG, "wifi request failed " + url + ": " + e);
            }
            writeState("wifi " + url + " -> " + (body == null ? "unreachable" : body));
            String text = body == null
                    ? "连不上 " + host + "（确认电视开着且和手机同一个 WiFi）"
                    : (hint == null ? body : hint + " → " + body);
            show(text);
        }).start();
    }

    private enum AdvMode { HID, RTK, CLONE }

    // ---------------------------------------------------------------- scan

    private void requestScan() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT}, SCAN_PERMISSION_REQUEST);
            return;
        }
        beginScan();
    }

    private void beginScan() {
        stopRemote();
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            show("请先打开手机蓝牙。"); return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            show("手机无法启动 BLE 扫描。"); return;
        }
        stopScan();
        captureKeys.clear();
        seenDevices.clear();
        scanPackets = 0;
        scanRetries = 0;
        openCaptureFile();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanChunk = 0;
        scanSession = true;
        startChunk(settings);
        showDetail("捕获 0 条广播；最近一条：无"
                + (captureFile == null ? "" : "；写入 " + captureFile.getName()));
        main.removeCallbacks(ticker);
        main.postDelayed(ticker, 1000);
    }

    private void startChunk(ScanSettings settings) {
        if (!scanSession) return;
        scanRetries = 0;
        startScanAttempt(settings);
        main.postDelayed(() -> {
            if (!scanSession) return;
            stopScanner();
            scanChunk++;
            if (scanChunk < SCAN_CHUNKS) {
                main.postDelayed(() -> startChunk(settings), SCAN_GAP_MILLIS);
            } else {
                finishScan();
            }
        }, SCAN_MILLIS);
    }

    private void stopScanner() {
        if (scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (SecurityException ignored) { }
        }
        scanning = false;
    }

    private void startScanAttempt(ScanSettings settings) {
        try {
            scanner.startScan(null, settings, scanCallback);
            scanning = true;
            show("正在扫描第 " + (scanChunk + 1) + "/" + SCAN_CHUNKS + " 段，共约 "
                    + (SCAN_CHUNKS * SCAN_MILLIS / 60000L) + " 分钟" + (scanRetries == 0 ? "" : "（第 " + (scanRetries + 1) + " 次尝试）")
                    + "：请靠近实体遥控器，同时按住“设置”和“音量减”进入对码，再点按方向/返回。");
        } catch (SecurityException e) {
            show("蓝牙扫描权限不足：" + e.getMessage());
        }
    }

    private static String scanErrorText(int code) {
        switch (code) {
            case 1: return "已有扫描在运行";
            case 2: return "应用注册失败";
            case 3: return "内部错误";
            case 4: return "硬件不支持";
            case 5: return "蓝牙资源被占用";
            case 6: return "扫描过于频繁";
            default: return "未知错误";
        }
    }

    private void finishScan() {
        scanSession = false;
        main.removeCallbacks(ticker);
        stopScanner();
        closeCaptureFile();
        String summary = "扫描结束：共收到 " + scanPackets + " 包、" + seenDevices.size()
                + " 个设备；相关广播 " + captures.size() + " 种。";
        Log.i(LOG_TAG, summary);
        show(summary + (captureFile == null ? "" : " 文件：" + captureFile.getAbsolutePath()));
    }

    private File captureFile;
    private BufferedWriter captureWriter;

    private void openCaptureFile() {
        closeCaptureFile();
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            if (dir != null && !dir.exists()) dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            captureFile = new File(dir, "captures_" + stamp + ".txt");
            captureWriter = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(captureFile, true), StandardCharsets.UTF_8));
            captureWriter.write("# rssi|address|name|ad_hex  (scan start " + stamp + ")\n");
            captureWriter.flush();
        } catch (IOException e) {
            captureFile = null;
            captureWriter = null;
            show("无法创建捕获文件：" + e);
        }
    }

    private void writeCaptureLine(String line) {
        if (captureWriter == null) return;
        try {
            captureWriter.write(line);
            captureWriter.write('\n');
            captureWriter.flush();
        } catch (IOException ignored) {
        }
    }

    private void closeCaptureFile() {
        if (captureWriter != null) {
            try { captureWriter.close(); } catch (IOException ignored) { }
        }
        captureWriter = null;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            ScanRecord record = result.getScanRecord();
            if (record == null) return;
            scanPackets++;
            String name = record.getDeviceName();
            if (name == null) name = result.getDevice().getName();
            byte[] bytes = record.getBytes();
            if (bytes == null) return;
            String packet = toHex(bytes);
            String address = result.getDevice().getAddress();
            if (address != null) seenDevices.add(address);
            // Every packet goes to the file, so nothing is lost even when the UI filter skips it.
            writeCaptureLine(String.format(Locale.US, "%d|%s|%s|%s",
                    result.getRssi(), address, name, packet));
            boolean hid = record.getServiceUuids() != null
                    && record.getServiceUuids().contains(new ParcelUuid(HID_SERVICE));
            boolean vendor = record.getManufacturerSpecificData() != null
                    && record.getManufacturerSpecificData().size() > 0;
            boolean serviceData = record.getServiceData() != null
                    && !record.getServiceData().isEmpty();
            if (!(hid || vendor || serviceData || (name != null && name.contains("CH")))) return;
            if (!captureKeys.add(packet)) return;
            captures.add(String.format(Locale.US, "%d|%s|%s", result.getRssi(), name, packet));
            Log.i(LOG_TAG, "rssi=" + result.getRssi() + " name=" + name + " hid=" + hid
                    + " vendor=" + vendor + " serviceData=" + serviceData + " ad=" + packet);
            final int count = captures.size();
            show("捕获 " + count + " 种相关广播（共 " + scanPackets + " 包），请继续按键。");
            showDetail("最近一条：" + describe(packet));
        }

        @Override public void onScanFailed(int errorCode) {
            Log.i(LOG_TAG, "scan failed code=" + errorCode);
            if ((errorCode == 1 || errorCode == 5 || errorCode == 6) && scanRetries < 3) {
                scanRetries++;
                show("扫描被系统拒绝（" + scanErrorText(errorCode) + "），1.2 秒后重试 "
                        + scanRetries + "/3");
                main.postDelayed(() -> {
                    if (scanner == null) return;
                    try { scanner.stopScan(scanCallback); } catch (SecurityException ignored) { }
                    startScanAttempt(new ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build());
                }, 1200);
            } else {
                scanning = false;
                closeCaptureFile();
                show("BLE 扫描失败：" + scanErrorText(errorCode) + "（" + errorCode + "）");
            }
        }
    };

    /** Human readable summary of the most recent capture so the payload can be recognised at a glance. */
    private static String describe(String packet) {
        List<String> parts = new ArrayList<>();
        byte[] data = hex(packet);
        int i = 0;
        while (i + 1 < data.length) {
            int length = data[i] & 0xff;
            if (length == 0 || i + 1 + length > data.length) break;
            int type = data[i + 1] & 0xff;
            String body = toHex(Arrays.copyOfRange(data, i + 2, i + 1 + length));
            switch (type) {
                case 0x01: parts.add("flags=" + body); break;
                case 0x02:
                case 0x03: parts.add("uuid16=" + body); break;
                case 0x06:
                case 0x07: parts.add("uuid128=" + body); break;
                case 0x08:
                case 0x09: parts.add("name=" + body); break;
                case 0x0A: parts.add("tx=" + body); break;
                case 0x16: parts.add("svcdata=" + body); break;
                case 0x19: parts.add("appearance=" + body); break;
                case 0xFF: parts.add("mfr=" + body); break;
                default: parts.add(String.format(Locale.US, "t%02x=%s", type, body)); break;
            }
            i += length + 1;
        }
        return String.join(" ", parts);
    }

    private void copyCaptures() {
        if (captures.isEmpty()) { show("还没有捕获数据。"); return; }
        StringBuilder out = new StringBuilder();
        for (String capture : captures) out.append(capture).append('\n');
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("RBE captures", out));
        Log.i(LOG_TAG, out.toString());
        show("已复制 " + captures.size() + " 条广播到剪贴板。");
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private void stopScan() {
        scanSession = false;
        main.removeCallbacks(ticker);
        stopScanner();
        closeCaptureFile();
    }

    // ------------------------------------------------------------ broadcast

    private void requestStart(AdvMode mode) {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE}, PERMISSION_REQUEST);
            return;
        }
        startRemote(mode);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == SCAN_PERMISSION_REQUEST) {
            if (results.length == 2 && results[0] == PackageManager.PERMISSION_GRANTED
                    && results[1] == PackageManager.PERMISSION_GRANTED) beginScan();
            else show("需要允许附近设备权限才能扫描实体遥控器。");
        } else if (requestCode == PERMISSION_REQUEST && results.length == 2
                && results[0] == PackageManager.PERMISSION_GRANTED
                && results[1] == PackageManager.PERMISSION_GRANTED) startRemote(pendingMode);
        else show("需要允许附近设备权限才能广播 BLE 遥控器。");
    }

    private AdvMode pendingMode = AdvMode.HID;

    private void startRemote(AdvMode mode) {
        pendingMode = mode;
        stopScan();
        if (server != null) { show("遥控器已启动，等待电视配对或连接。"); return; }
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            show("请先打开手机蓝牙。"); return;
        }
        AdvertiseData packet;
        AdvertiseData response;
        try {
            if (mode == AdvMode.CLONE) {
                if (captures.isEmpty()) loadCapturesFromDisk();
                String target = captures.isEmpty()
                        ? "00:E0:29:EC:F2:32|CH BT RC|" + REMOTE_ADV_FALLBACK
                        : pickCloneTarget();
                AdvertiseData[] pair = cloneAdvertise(target);
                packet = pair[0];
                response = pair[1];
            } else if (mode == AdvMode.RTK) {
                packet = new AdvertiseData.Builder()
                        .addServiceUuid(new ParcelUuid(HID_SERVICE))
                        .addManufacturerData(RTK_COMPANY_ID, RTK_COMPANY_DATA).build();
                response = new AdvertiseData.Builder().setIncludeDeviceName(true).build();
            } else {
                packet = new AdvertiseData.Builder()
                        .addServiceUuid(new ParcelUuid(HID_SERVICE)).build();
                response = new AdvertiseData.Builder().setIncludeDeviceName(true).build();
            }
        } catch (IllegalArgumentException e) {
            show("广播包构造失败：" + e.getMessage()); return;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            show("这台手机当前不支持 BLE 外设广播，无法模拟实体遥控器。"); return;
        }
        formerName = adapter.getName();
        if (!REMOTE_NAME.equals(formerName) && !adapter.setName(REMOTE_NAME)) {
            show("无法临时设置蓝牙名称 CH BT RC，请检查系统限制。"); return;
        }
        server = manager.openGattServer(this, callback);
        if (server == null) {
            restoreName();
            show("无法启动 GATT 服务。"); return;
        }
        buildServices();
        nextService = 1;
        pendingPacket = packet;
        pendingResponse = response;
        if (!server.addService(services.get(0))) {
            show("添加蓝牙设备信息服务失败。"); stopRemote();
        } else show("正在建立 BLE HID 服务…");
    }

    private AdvertiseData pendingPacket;
    private AdvertiseData pendingResponse;

    /** Read every capture file back so cloning works without re-scanning in this app run. */
    private void loadCapturesFromDisk() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        File[] all = dir == null ? null : dir.listFiles();
        if (all == null) return;
        Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int before = captures.size();
        for (File file : all) {
            if (file == null || !file.isFile() || !file.getName().startsWith("captures_")) continue;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#")) continue;
                    String[] parts = line.split("\\|");
                    if (parts.length >= 4 && parts[3].length() >= 8) captures.add(line);
                }
            } catch (IOException e) {
                Log.i(LOG_TAG, "读取捕获文件失败 " + file.getName() + "：" + e);
            }
        }
        Log.i(LOG_TAG, "从文件恢复捕获 " + (captures.size() - before) + " 条");
    }

    /** Prefer the newest capture whose device name is the real remote, else the newest of any device. */
    private String pickCloneTarget() {        for (int i = captures.size() - 1; i >= 0; i--) {
            if (captures.get(i).contains("CH BT")) return captures.get(i);
        }
        return captures.get(captures.size() - 1);
    }

    /** Rebuild advertise + scan response exactly as the captured remote sends them. */
    private AdvertiseData[] cloneAdvertise(String capture) {
        String packetHex = capture.substring(capture.lastIndexOf('|') + 1);
        byte[] data = hex(packetHex);
        AdvertiseData.Builder builder = new AdvertiseData.Builder();
        AdvertiseData.Builder extra = new AdvertiseData.Builder();
        Set<Integer> companies = new HashSet<>();
        List<String> skipped = new ArrayList<>();
        int i = 0;
        while (i + 1 < data.length) {
            int length = data[i] & 0xff;
            if (length == 0 || i + 1 + length > data.length) break;
            int type = data[i + 1] & 0xff;
            byte[] body = Arrays.copyOfRange(data, i + 2, i + 1 + length);
            switch (type) {
                case 0x02:
                case 0x03:
                    for (int k = 0; k + 1 < body.length; k += 2) {
                        int shortId = (body[k] & 0xff) | ((body[k + 1] & 0xff) << 8);
                        builder.addServiceUuid(new ParcelUuid(uuid(shortId)));
                    }
                    break;
                case 0x06:
                case 0x07:
                    for (int k = 0; k + 15 < body.length; k += 16) {
                        long msb = 0, lsb = 0;
                        for (int b = 0; b < 8; b++) msb = (msb << 8) | (body[k + b] & 0xff);
                        for (int b = 8; b < 16; b++) lsb = (lsb << 8) | (body[k + b] & 0xff);
                        builder.addServiceUuid(new ParcelUuid(new UUID(msb, lsb)));
                    }
                    break;
                case 0x16:
                    if (body.length >= 2) {
                        int shortId = (body[0] & 0xff) | ((body[1] & 0xff) << 8);
                        builder.addServiceData(new ParcelUuid(uuid(shortId)),
                                Arrays.copyOfRange(body, 2, body.length));
                    }
                    break;
                case 0xFF:
                    if (body.length >= 2) {
                        int company = (body[0] & 0xff) | ((body[1] & 0xff) << 8);
                        byte[] payload = Arrays.copyOfRange(body, 2, body.length);
                        if (companies.add(company)) builder.addManufacturerData(company, payload);
                        else extra.addManufacturerData(company, payload);
                    }
                    break;
                case 0x01:
                    skipped.add("flags");
                    break;
                case 0x08:
                case 0x09:
                    skipped.add("name");
                    break;
                case 0x0A:
                    skipped.add("txpower");
                    break;
                case 0x19:
                    skipped.add("appearance");
                    break;
                default:
                    skipped.add(String.format(Locale.US, "t%02x", type));
                    break;
            }
            i += length + 1;
        }
        builder.setIncludeDeviceName(true);
        if (!skipped.isEmpty()) {
            Log.i(LOG_TAG, "clone skipped fields (system managed): " + skipped);
            show("克隆广播：由系统接管的字段 " + skipped);
        }
        return new AdvertiseData[]{builder.build(), extra.build()};
    }

    private void buildServices() {
        services.clear();
        BluetoothGattService info = new BluetoothGattService(DEVICE_INFO_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        info.addCharacteristic(readOnly(MANUFACTURER, "CHANGHONG".getBytes(StandardCharsets.UTF_8)));
        info.addCharacteristic(readOnly(MODEL, "RBE902VC".getBytes(StandardCharsets.UTF_8)));
        // PnP ID: vendor source USB-IF, VID 0x0416, PID 0x0300, version 0x0111.
        info.addCharacteristic(readOnly(PNP_ID,
                new byte[]{2, 0x16, 0x04, 0, 0x03, 0x11, 0x01}));
        services.add(info);

        BluetoothGattService battery = new BluetoothGattService(BATTERY_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        battery.addCharacteristic(readOnly(BATTERY_LEVEL, new byte[]{100}));
        services.add(battery);

        BluetoothGattService hid = new BluetoothGattService(HID_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        hid.addCharacteristic(readOnly(HID_INFO, new byte[]{0x11, 0x01, 0, 0x02}));
        hid.addCharacteristic(readOnly(HID_REPORT_MAP, REPORT_MAP));
        BluetoothGattCharacteristic mode = new BluetoothGattCharacteristic(HID_PROTOCOL_MODE,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        mode.setValue(new byte[]{1});
        hid.addCharacteristic(mode);
        BluetoothGattCharacteristic control = new BluetoothGattCharacteristic(HID_CONTROL,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        hid.addCharacteristic(control);
        keyboardReport = addInputReport(hid, 1, 8);
        systemReport = addInputReport(hid, 9, 1);
        addOutputReport(hid, 1, 1);
        // The TV's voice dongle service writes two reports that the real remote exposes but the
        // report map does not describe: report 0xFB (1 byte) and report 0xFA (20 bytes). Without
        // them the host logs "bta_hh_le_write_rpt: no matching report" and tears the HID link down
        // a couple of seconds after it opens.
        addOutputReport(hid, 0xFB, 1);
        addOutputReport(hid, 0xFA, 20);
        addInputReport(hid, 3, 2);
        addInputReport(hid, 8, 20);
        addInputReport(hid, 0xFC, 20);
        addInputReport(hid, 0xF8, 20);
        addInputReport(hid, 0xF9, 1);
        services.add(hid);
    }

    private BluetoothGattCharacteristic addInputReport(BluetoothGattService service, int id, int size) {
        BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(HID_REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED);
        // The characteristic value carries the report id as its first byte, as every HOGP device
        // with report ids does; the TV's hidraw reader sees report id + payload (9 bytes for report 1).
        byte[] initial = new byte[size + 1];
        initial[0] = (byte) id;
        c.setValue(initial);
        BluetoothGattDescriptor ref = new BluetoothGattDescriptor(REPORT_REFERENCE,
                BluetoothGattDescriptor.PERMISSION_READ);
        ref.setValue(new byte[]{(byte) id, 1});
        c.addDescriptor(ref);
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(CLIENT_CONFIG,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED
                        | BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED);
        cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        c.addDescriptor(cccd);
        service.addCharacteristic(c);
        if (id == 3) consumerReport = c;
        return c;
    }

    private void addOutputReport(BluetoothGattService service, int id, int size) {
        BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(HID_REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
                        | BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED);
        byte[] initial = new byte[size + 1];
        initial[0] = (byte) id;
        c.setValue(initial);
        BluetoothGattDescriptor ref = new BluetoothGattDescriptor(REPORT_REFERENCE,
                BluetoothGattDescriptor.PERMISSION_READ);
        ref.setValue(new byte[]{(byte) id, 2});
        c.addDescriptor(ref);
        service.addCharacteristic(c);
    }

    private void startAdvertising() {
        main.postDelayed(() -> {
            if (server == null || advertiser == null) return;
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true).setTimeout(0).build();
            AdvertiseData packet = pendingPacket != null ? pendingPacket
                    : new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(HID_SERVICE)).build();
            AdvertiseData response = pendingResponse != null ? pendingResponse
                    : new AdvertiseData.Builder().setIncludeDeviceName(true).build();
            try {
                advertiser.startAdvertising(settings, packet, response, advertiseCallback);
            } catch (IllegalArgumentException | SecurityException e) {
                show("广播包被系统拒绝：" + e.getMessage());
            }
        }, 1000);
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settings) {
            advertising = true;
            show("正在广播 CH BT RC。电视端打开蓝牙配对，靠近电视 1 米内再试。");
        }
        @Override public void onStartFailure(int errorCode) {
            show("BLE 广播失败，错误码 " + errorCode + "。请检查手机是否支持外设广播。");
        }
    };

    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int status, BluetoothGattService service) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                show("添加 GATT 服务失败：" + service.getUuid() + "，状态 " + status);
                return;
            }
            if (server == null) return;
            if (nextService < services.size()) {
                if (!server.addService(services.get(nextService++))) show("添加 GATT 服务请求失败。");
            } else startAdvertising();
        }

        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = device;
                // The real remote leaves pairing mode the moment the TV accepts it (the user lets go
                // of 设置+音量减), so the TV's autopair plugin stops seeing the pairing advertisement.
                // Keep advertising and the plugin finds it again, removes the bond and re-pairs in a
                // loop. Stop the advertisement while the link is up, exactly like the remote does.
                if (advertiser != null && advertising) {
                    advertiser.stopAdvertising(advertiseCallback);
                    advertising = false;
                }
                show("电视已连接（配对状态 " + device.getBondState() + "）；等它订阅 HID 通知后即可按键。");
                writeState("connected");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = null;
                notificationsEnabled = false;
                if (advertiser != null && !advertising) startAdvertising();
                show("电视断开，已恢复广播等待重新连接。");
                writeState("disconnected status=" + status);
            }
        }

        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value == null) value = new byte[0];
            if (offset > value.length) server.sendResponse(device, requestId,
                    BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
            else server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                    Arrays.copyOfRange(value, offset, value.length));
        }

        @Override public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattDescriptor descriptor) {
            byte[] value = descriptor.getValue();
            if (value == null) value = new byte[0];
            if (offset > value.length) server.sendResponse(device, requestId,
                    BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
            else server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                    Arrays.copyOfRange(value, offset, value.length));
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded,
                int offset, byte[] value) {
            if (!preparedWrite && offset == 0) {
                descriptor.setValue(value);
                if (descriptor.getCharacteristic() == keyboardReport && CLIENT_CONFIG.equals(descriptor.getUuid())) {
                    notificationsEnabled = Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (notificationsEnabled) show("电视已订阅遥控按键，可以测试方向与音量。");
                }
                writeState("cccd char=" + descriptor.getCharacteristic().getUuid()
                        + " desc=" + descriptor.getUuid()
                        + " value=" + toHex(value)
                        + " notify=" + notificationsEnabled);
            }
            if (responseNeeded) server.sendResponse(device, requestId,
                    preparedWrite || offset != 0 ? BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED : BluetoothGatt.GATT_SUCCESS,
                    offset, null);
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded,
                int offset, byte[] value) {
            if (!preparedWrite && offset == 0) {
                characteristic.setValue(value);
                if (HID_REPORT.equals(characteristic.getUuid()) && value != null && value.length > 0) {
                    int id = value[0] & 0xFF;
                    if (id == 0xFA || id == 0xFB)
                        showDetail("电视写入报表 0x" + Integer.toHexString(id) + "：" + toHex(value));
                }
            }
            if (responseNeeded) server.sendResponse(device, requestId,
                    preparedWrite || offset != 0 ? BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED : BluetoothGatt.GATT_SUCCESS,
                    offset, null);
        }
    };

    // ------------------------------------------------- auto repeat + custom usage

    // The real remote emits a fresh press every few tens of milliseconds while a direction or
    // volume key stays held, so holding a button here presses again on a timer instead of relying on
    // one long-down report that the TV's input stack may not repeat.
    private static final long REPEAT_DELAY_MILLIS = 350;
    private static final long REPEAT_INTERVAL_MILLIS = 150;

    private Runnable repeatTask;
    private int[] repeatUsages;

    private Button repeatButton(String label, int... usages) {
        Button b = button(label, null);
        b.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startRepeat(usages);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopRepeat();
                    return true;
                default:
                    return false;
            }
        });
        return b;
    }

    private void startRepeat(int... usages) {
        if (!canSend()) return;
        stopRepeat();
        repeatUsages = usages;
        sendKeys(usages);
        repeatTask = new Runnable() {
            @Override public void run() {
                if (repeatUsages == null) return;
                sendKeys(repeatUsages);
                main.postDelayed(this, REPEAT_INTERVAL_MILLIS);
            }
        };
        main.postDelayed(repeatTask, REPEAT_DELAY_MILLIS);
    }

    private void stopRepeat() {
        if (repeatTask != null) { main.removeCallbacks(repeatTask); repeatTask = null; }
        repeatUsages = null;
    }

    private boolean canSend() {
        if (server != null && connected != null && notificationsEnabled) return true;
        writeState("blocked server=" + (server != null) + " connected=" + (connected != null)
                + " notify=" + notificationsEnabled);
        show("电视尚未连接并启用 HID 按键通知。");
        return false;
    }

    /**
     * Mirror the HID state into files/state.txt so a sweep can read it with
     * {@code adb shell run-as org.local.rbe902vc cat files/state.txt} without fighting the screen.
     */
    void writeState(String note) {
        String text = "connected=" + (connected == null ? "none" : connected.getAddress())
                + " notify=" + notificationsEnabled
                + " advertising=" + advertising
                + " note=" + note;
        File dir = getExternalFilesDir(null);
        File target = dir == null ? new File(getFilesDir(), "state.txt") : new File(dir, "state.txt");
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    /** Send whatever hex usages are typed in the custom field, for sweeping keys not yet found. */
    private void sendCustom() {
        String text = custom == null ? "" : custom.getText().toString().trim();
        if (text.isEmpty()) { show("先填 usage，十六进制。"); return; }
        sendHex(text);
    }

    /** Parse "AF" / "0xAF" / "B2,DD" and send it as report 1. Shared with {@link SendReceiver}. */
    void sendHex(String text) {
        String[] parts = text.trim().split("[,，\\s]+");
        int[] usages = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();
                if (part.startsWith("0x") || part.startsWith("0X")) part = part.substring(2);
                usages[i] = Integer.parseInt(part, 16) & 0xFF;
            }
        } catch (NumberFormatException e) {
            show("usage 格式不对：" + text); return;
        }
        sendKeys(usages);
        writeState("sendHex " + text);
    }

    /** adb entry point for broadcasting without touching the screen: hid | rtk | clone. */
    void startAdvertising(String mode) {
        if (mode == null) return;
        switch (mode.toLowerCase()) {
            case "hid": requestStart(AdvMode.HID); break;
            case "rtk": requestStart(AdvMode.RTK); break;
            case "clone": requestStart(AdvMode.CLONE); break;
            default: show("未知广播模式：" + mode); break;
        }
        writeState("startAdvertising " + mode);
    }

    /** adb entry point to stop advertising and scanning. */
    void stopAll() {
        stopScan();
        stopRemote();
    }

    /**
     * 报表 1（键盘）：特征值就是 8 字节报文本体，不带报表 id。
     * 电视侧 hidraw 读到的是 “报表 id + 这 8 字节”（真遥控器按键时是 read len = 9），
     * 所以这里第 1 字节是修饰键、第 2 字节保留、第 3..8 字节是 6 个用法码。
     */
    private void sendKeys(int... usages) {
        if (!canSend()) return;
        BluetoothDevice target = connected;
        byte[] pressed = new byte[8];
        for (int i = 0; i < usages.length && i < 6; i++) pressed[2 + i] = (byte) usages[i];
        int result = server.notifyCharacteristicChanged(target, keyboardReport, false, pressed);
        writeState("send usages=" + Arrays.toString(usages) + " result=" + result);
        if (result != BluetoothStatusCodes.SUCCESS) {
            show("发送按键失败，状态 " + result); return;
        }
        main.postDelayed(() -> {
            if (server != null && connected == target)
                server.notifyCharacteristicChanged(target, keyboardReport, false, new byte[8]);
        }, 110);
    }

    /**
     * 电源键。走报表 9（System Control）里的 Generic Desktop 0x81 "System Power Down"：
     * 电视内核 hid-input 把 HID_UP_GENDESK 0x81 映射成 KEY_POWER，Android 再变成 KEYCODE_POWER(26)。
     * 键盘报表和 Consumer 报表这条路都不通（keylayout 里 POWER 全被注释，Consumer 0x30 也没反应），
     * 所以第 169 字节之后追加了这个 System Control collection。
     */
    private void sendPower() {
        if (systemReport == null) { show("遥控器未启动：先点“连接电视（克隆广播）”。"); return; }
        if (!canSend()) return;
        BluetoothDevice target = connected;
        int result = server.notifyCharacteristicChanged(target, systemReport, false, new byte[]{1});
        writeState("power system usage=0x81 result=" + result);
        if (result != BluetoothStatusCodes.SUCCESS) { show("发送电源失败，状态 " + result); return; }
        show("已发送电源（System Power Down）。电视应当待机；开机要用实体遥控器。");
        main.postDelayed(() -> {
            if (server != null && connected == target)
                server.notifyCharacteristicChanged(target, systemReport, false, new byte[]{0});
        }, 110);
    }

    /** 备用电源 1：Consumer 报表 3 的 usage 0x30（Power），实测在长虹这台电视上没有反应。 */
    private void sendPowerConsumer() {
        if (!sendConsumer(POWER_CONSUMER)) return;
        show("已发送 Consumer 0x30（备用）。");
        writeState("power consumer=0x30");
    }

    /** 备用电源 2：键盘报表扫描码 116（0x74），keylayout 已注释，必然无反应，只作对照。 */
    private void sendPowerScancode() {
        sendKeys(POWER_SCANCODE);
        show("已发送扫描码 116（0x74，备用）。");
        writeState("power scancode=0x74");
    }

    private boolean sendConsumer(int usage) {
        if (consumerReport == null) { show("遥控器未启动：没有 Consumer 报表。先点“连接电视（克隆广播）”。"); return false; }
        if (!canSend()) return false;
        BluetoothDevice target = connected;
        byte[] pressed = new byte[2];
        pressed[0] = (byte) (usage & 0xFF);
        pressed[1] = (byte) ((usage >> 8) & 0xFF);
        int result = server.notifyCharacteristicChanged(target, consumerReport, false, pressed);
        writeState("send consumer=0x" + Integer.toHexString(usage) + " result=" + result);
        if (result != BluetoothStatusCodes.SUCCESS) { show("发送失败，状态 " + result); return false; }
        main.postDelayed(() -> {
            if (server != null && connected == target)
                server.notifyCharacteristicChanged(target, consumerReport, false, new byte[]{0, 0});
        }, 110);
        return true;
    }

    private void restoreName() {        if (adapter != null) {
            String restore = formerName;
            if (restore == null && REMOTE_NAME.equals(adapter.getName()))
                restore = Settings.Global.getString(getContentResolver(), "device_name");
            if (restore != null && !REMOTE_NAME.equals(restore)) adapter.setName(restore);
        }
        formerName = null;
    }

    private void stopRemote() {
        if (advertiser != null && advertising) advertiser.stopAdvertising(advertiseCallback);
        advertising = false;
        if (server != null) { server.clearServices(); server.close(); server = null; }
        connected = null;
        notificationsEnabled = false;
        restoreName();
        show("已停止 BLE 广播并恢复手机蓝牙名称。");    }

    @Override protected void onDestroy() {
        if (current == this) current = null;
        stopRepeat();
        stopScan();
        stopRemote();
        super.onDestroy();
    }
}
