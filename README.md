# RBE902VC 蓝牙遥控器（手机端）

目标：荣耀 70 Pro（Android 14）作为 BLE HID 外设，代替长虹电视（hisi551_SEC，Android 5.1.1）的原装遥控器。

实体遥控器的**对码组合键：同时按住“设置”键和“音量减”键**，电视端打开“蓝牙遥控器配对”界面后按这个组合，遥控器才会进入对码广播状态；**电视一配对成功就松手**，遥控器随即退出对码状态。

**当前状态（2026-11-10 实测通过）**：手机广播被电视 autopair 接受 → 配对成功 → HID 链路稳定不解绑 → 方向/确定/返回/音量/设置/换源/主页/快捷/观影全部在电视上生效，方向与音量支持按住连发。版本 v1.8（`versionCode=18`）。

## 实测证据（电视侧日志）

配对与建链（电视 logcat，手机 RPA `69:5D:76:xx:xx:xx`）：

```
BluetoothRTKAutoPairService: [Good Signal(0 - -48 > 50)] RCU ... rssi[-48]
BluetoothRTKAutoPairService: [FOUND] 69:5D:76:xx:xx:xx
BluetoothBondStateMachine: Bond State Change Intent OldState: 11 NewState: 12
BluetoothRTKAutoPairService: Sdp or Gatt search success(1)  Service=00001812-0000-1000-8000-00805f9b34fb
/hidraw0 open succussfully!
bta_hh_le_write_rpt: ReportID: 0xfb Data Len: 1      ← 语音服务写输出报表 1 字节
bta_hh_le_write_rpt: ReportID: 0xfa Data Len: 20     ← 语音服务写输出报表 20 字节
```

按键（`interceptKeyTi ... deviceName=CH BT RC`）：

| 手机发出的 usage | 电视 scancode | 电视 keycode | 实际效果 |
| --- | --- | --- | --- |
| `0xBC` 上 | 188 | 19 | 上 |
| `0xBD` 下 | 189 | 20 | 下 |
| `0xBE` 左 | 190 | 21 | 左 |
| `0xBF` 右 | 191 | 22 | 右 |
| `0xC6` 确定 | 198 | 23 | 确定 |
| `0xC7` 返回 | 199 | 4 | 返回 |
| `0xDC` 音量＋ | 220 | 24 | 音量＋ |
| `0xDD` 音量－ | 221 | 25 | 音量－ |
| `0xB2` 设置 | 178 | 4125（CHANGHONGIR_SETTINGS） | 弹出系统设置 |
| `0xB7` 换源 | 183 | 2001（SOURCE） | 弹出“节目源”面板（HDMI1/HDMI2/AV/数字电视/模拟电视），5 秒后进入选中源 |
| `0xD5` 输入源 | 213 | 4102（CHANGHONGIR_INPUT） | 只有个别界面处理；桌面/播放器里到达但不动作，留作备用 |
| `0xBA` 主页 | 186 | 4124（CHANGHONGIR_HOME） | 桌面/主页 |
| `0xEB` 快捷 | 235 | 4160（CHANGHONGIR_SHORTCUT） | 快捷面板 |
| `0xB4` 观影 | 180 | 4148（CHANGHONGIR_MOVIE） | 观影入口 |

`0xB2` 是从 `CH_BT_RC.kl` 的 `key 178 CHANGHONGIR_SETTINGS`（与其余八键同一段恒等映射）推出来的，**已在电视上实测确认**：`keycode=4125`，按下去电视弹系统设置。`设置＋音量－` 同帧到达 `keycode=4125` + `keycode=25`。

`换源` 的定案过程：先按 `CHANGHONGIR_INPUT` 试了 `0xD5`，电视收到 `keycode=4102` 但桌面与播放器都不响应（`Unhandled key`）；随后回到 `CH_BT_RC.kl` 找到 `key 183 SOURCE`，发 `0xB7` → `keycode=2001` → 电视弹出“节目源”面板，实测通过（2026-11-10 23:00）。方向与音量键按住会连发（350 ms 后每 150 ms 一帧），与实体遥控器一致。

## 从电视逆向得到的三条硬约束

1. **广播必须带厂商数据 `0x000D` + `383802000001`**
   `/etc/bluetooth/rtkbt_autopair_config.xml` 里 `<specidata_value>0D:00:38:38:02:00:00:01`、掩码全字节匹配。电视配对界面不列普通 BLE 设备，只认这条厂商数据。
2. **RSSI 必须优于 -50 dBm**
   配置 `<Autopair_rssi><rssi>50</rssi>` 的实际判定是 `Weak Signal(0 - -48 > 50)` 通过、`-56/-68/-84` 被拒 —— 数值是“距离门限”的另一种写法，等价于信号强度要好于 **-50 dBm**。手机得贴近电视底部（50 cm 内）。
3. **HID 必须提供输出报表 `0xFB`(1 字节) 与 `0xFA`(20 字节)**
   电视的语音 dongle 服务（`com.iflytek.xiri` 的 `DONGLE_SERVICE` / `VendorRemoteBle`）在建链后立刻写这两个报表；缺了它日志就是 `bta_hh_le_write_rpt: no matching report`，然后 `bta_hh_co_close` → `HidService 2->0` → Bond 12→10，电视自己解绑。这也是“连上两秒就断、反复弹配对框”的根因。

另外：电视 autopair 会**每次扫到对码广播就重新配对**（`removeOldRCU` → `removeBond` → `createBond`），所以手机一建链就必须停止广播，模拟实体遥控器松手退出对码状态；断开后再自动恢复广播。

## 实体遥控器的完整广播（克隆源）

地址 `00:E0:29:xx:xx:xx`，名称 `CH BT RC`，AD 记录：

```
0201050303121809ff0d003838020000010909434820425420524312ff0d00ffff52424635303056435632303237
```

- 广播包：Flags `0x05`、Complete UUID16 `0x1812`(HID)、Manufacturer `0x000D` = `383802000001`、Complete Local Name `CH BT RC`
- 扫描响应包：Manufacturer `0x000D` = `FFFF` + ASCII `RBF500VCV2027`

本机 `0x000D` 是 Realtek 的 company id，所以广播与扫描响应里各放一条同 company 的厂商数据 —— Android 的 `AdvertiseData.Builder` 不允许同一包内重复 company，因此第二条放在扫描响应里。

## 源码与构建

- 源码：`app/src/main/java/org/local/rbe902vc/`（`MainActivity.java` 单 Activity，无第三方依赖，API 34；`SendReceiver.java` 是 adb 调试入口）。
- `build.ps1`：项目自带 `toolchain/jdk` + `sdk/build-tools/34.0.0`，依次 javac → d8 → aapt2 link → 合 dex → zipalign → apksigner（`~/.android/debug.keystore`），不改系统 SDK/JDK。它会把 `app/src/main/java` 下所有 `.java` 一起编译（曾经只编译 `MainActivity.java`，导致 `SendReceiver` 没进 dex）。
  ```
  pwsh -File build.ps1 -Version 1.8 -Output RBE902VC_BLE_v1.8.apk
  ```
- `verify_source.py`：把源码常量逐一核对回电视采集数据（HID 描述符 169 字节、`CH_BT_RC.kl` 的 9 个用途码、vhid 插件 PnP ID、autopair 厂商数据、输出报表 `0xFB`/`0xFA`、按键报文 9 字节结构、换源键 `0xB7`↔`key 183 SOURCE`），7 项断言全过才继续：
  ```
  python verify_source.py
  ```
- 版本号写在 `app/src/main/AndroidManifest.xml`：aapt2 的 `--version-code/--version-name` 不覆盖 manifest 里的硬编码值。

## adb 调试入口

`SendReceiver` 支持用广播直接发键，免去点屏幕：

```
adb shell am broadcast -n org.local.rbe902vc/.SendReceiver -a org.local.rbe902vc.SEND --es u B7
adb shell am broadcast -n org.local.rbe902vc/.SendReceiver -a org.local.rbe902vc.SEND --es u B2,DD
adb shell am broadcast -n org.local.rbe902vc/.SendReceiver -a org.local.rbe902vc.SEND --es adv clone
adb shell am broadcast -n org.local.rbe902vc/.SendReceiver -a org.local.rbe902vc.SEND --ez stop true
```

必须带 `-n org.local.rbe902vc/.SendReceiver`：Android 14 不给 manifest 声明的接收器投递隐式广播，省略组件名时命令照样回 `result=0`，但应用收不到。

当前状态另有一份镜像文件，便于脚本判断链路：

```
adb shell cat /sdcard/Android/data/org.local.rbe902vc/files/state.txt
# connected=BC:1C:81:xx:xx:xx notify=true advertising=false note=cccd char=00002a4d-... value=0100
```

## 电源：BLE 关机方案已放弃（保留调查记录）

> **状态（2026-11-12 决定）：不再做电源键。** 电视上所有相关改动已用 `tools/tv/cleanup_tv.sh` 清除：
> 键位表 bind mount 全部卸载、`/data/local/keylayout` 与 `/data/system/devices` 删除、
> `webremote/boot.sh` 的开机钩子已从备份还原。原厂键位表回到 `# key 116 POWER`（注释）状态，
> 实测 `scancode=183 → keyCode=2001`，厂商键位完好。
>
> 结论：这台 ROM 的 EventHub 不接受任何运行期覆盖的键位表（文件 bind mount、目录 bind mount 都会
> 回落到 `Generic.kl`，`/data/system/devices/keylayout` 干脆不查），而 `/system` 只读、remount 被拒
> （root + 全 Caps + SELinux Disabled 也返回 `Operation not permitted`）。所以在**不改 /system**
> 的前提下，无法同时拥有「电源键」和「厂商键位」；选择保留全键位。

以下是最初的调查记录，供以后换机器/换固件时参考。

这台机器把 KEYCODE_POWER(26) 映射成**整机断电**。BLE 关卡全在键位表上，逐条试过：

| 试法 | 结果 |
| --- | --- |
| 键盘报表发扫描码 116（`0x74`） | 用法码 ≠ 扫描码：内核把 0x74 映射成 **134**，不是 116 |
| Consumer 页 0x30（Power） | 电视能收到（`recv report id: 03`），内核不认成键 |
| System Control 报表 9 的 **0x81 System Power Down** | 内核给出 `MSC_SCAN 00010081` → `EV_KEY KEY_POWER`（scancode 116），但 `Vendor_0416_Product_0300.kl` 里 `key 116 POWER` 被注释 → keyCode 0 |

补映射的老路都堵着：`mount -o remount,rw /system` 返回 `Operation not permitted`（root + 全 Caps + SELinux Disabled 也拒）；`/data/system/devices/keylayout/` 这个 ROM 不认；`nsenter` 不存在。

**最后走通的是 bind mount**：不重挂分区、不改 `/system` 一个字节，只把 `/data` 上的改好副本盖到键位表文件上。探测脚本 `tools/tv/probe_keylayout.sh` 的 5c 一步验证了这条路可行（`remount` 失败但 `mount -o bind` 成功）。

电视端部署（adb 已是 root）：

```bash
adb push tools/tv/klbind.sh /data/local/keylayout/klbind.sh
adb shell sh /data/local/keylayout/klbind.sh install    # 挂上（幂等）
adb shell sh /data/local/keylayout/klbind.sh status     # 验收：要看到 key 116   POWER
adb shell sh /data/local/keylayout/klbind.sh remove     # 还原原厂键位表
adb push tools/tv/install_boot_hook.sh /data/local/tmp/ && adb shell sh /data/local/tmp/boot_hook.sh
```

- 只多出 `key 116 POWER` / `key 152 POWER` 两行，**厂商键位（换源/设置/观影/数字/频道）一行不改**；
- 开机持久化：挂在 `/data/local/webremote/boot.sh` 里（该脚本由 `/system/etc/script/ch_system_init.sh` 以 root 调起），已实测重启后 `mount` 与 `key 116` 自动恢复，备份在 `boot.sh.bak.klbind`；
- 生效时机：EventHub 只在**输入设备创建时**读键位表，所以改完要在手机上点一次「断开重连」。

实测结果：发报表 9 后电视 10 秒内从网络消失（整机断电）；`getevent` 里能看到 `EV_MSC MSC_SCAN 00010081` + `EV_KEY KEY_POWER DOWN/UP`。

APP 里主界面 **「电源」** 就是发报表 9；调试区保留 `切到电源档 / 切回遥控档 / 电源备用 0x74 / 电源备用 C0x30` 这些历史通道，和「断开重连」「键位表状态」。

> 备用方案（`Generic.kl` 切档：PnP PID 0x0301 + 蓝牙名 RBE902VC）不再需要：那档只有电源键有效，且每次切档电视会解绑、重新配对要输配对码。代码保留但只作调试用。

**开机做不到**：本机没有红外发射器，电视断电后只能用实体遥控器开。

## 用法

1. `adb install -r RBE902VC_BLE_v2.2.apk`，打开应用，允许“附近设备”权限。
2. 电视进入“蓝牙遥控器配对”页（同时按住设置＋音量减的提示页）。
3. 手机点**连接电视（克隆广播）**，把手机贴到电视底部（< 50 cm）。
4. 电视识别后自动配对建链，界面会显示“电视已连接”，随后“已订阅遥控按键”。此时手机自动停止广播。
5. 用按键操作电视（方向/音量按住连发）。**停止广播 / 停止扫描**退出。
6. 关机走「网络」区的 WiFi 按钮（见上一节）；BLE 的「电源」是报表 9 那条实验通道。

## 已知未完成

- 语音键（走 `0xFA` 20 字节 dongle 协议，与 `com.iflytek.xiri` 对话）未实现，只做了“能收下电视写来的报表”这一层；用户明确暂时不需要。
- 红外开机做不到：本机没有红外发射器，电视断电后只能用实体遥控器开。
- `Autopair_type 136`、`<Key_pair 1..4>` 含义未解析。
