# 小米背屏开关 · Xiaomi BackScreen Switch

通过 [Shizuku](https://shizuku.rikka.app/) 开启、唤醒和关闭小米手机背屏的小工具，使用 Kotlin、Jetpack Compose 和 Material 3，无需 root。

- 一键关闭背屏，或开启并唤醒背屏。
- 区分“已关闭”“已开启但休眠”和“亮屏”等状态。
- 默认操作 Display 1，可在设置中修改；不允许操作主屏 0。
- 跟随系统深浅色和动态配色，支持 Android 主题图标。
- 前台静默刷新状态，开关操作完成后再次确认结果。

## 使用

需要带背屏的小米手机、Android 11 或更高版本，以及支持 `cmd display enable-display` / `disable-display` 的系统。具体能力取决于机型与系统版本。

1. 安装并启动 [Shizuku](https://shizuku.rikka.app/download/)。无 root 手机可通过无线调试启动。
2. 安装 APK，打开“背屏开关”，点击“授予权限”并允许 Shizuku 授权。
3. 确认副屏编号，点击“关闭背屏”或“开启并唤醒”。

手机重启后需重新启动 Shizuku。唤醒后的背屏仍按手机设置自动熄屏；系统不支持定向唤醒时，App 会提示使用背屏手势唤醒。

## 构建

工具版本和任务统一由 [mise](https://mise.jdx.dev/) 管理：

```bash
mise trust
mise install
mise run sdk
mise run package
```

`sdk` 会接受 Android SDK 许可证并安装编译依赖。`package` 运行 Android Lint、控制逻辑测试并构建 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

常用任务：`mise run build` 构建、`mise run verify` 检查与测试、`mise run install` 安装、`mise run export-apk` 导出到 `dist/`。多设备连接时，通过 `ANDROID_SERIAL` 指定目标设备。

首次构建会生成 `.signing/debug.keystore`，该文件不会提交到 Git。请保留自己的签名文件，后续更新须使用相同签名。

## 项目结构

```text
app/src/main/java/…/backscreen/   原生界面、状态管理与 Shizuku 服务
app/src/main/aidl/               受限的显示器控制接口
app/src/main/res/                图标与主题资源
app/src/test/                   控制逻辑回归测试
mise.toml                       工具版本与开发任务
```

## 许可证

项目采用 [MIT License](LICENSE)。第三方依赖与图标的许可说明见 [NOTICE.md](NOTICE.md)。
