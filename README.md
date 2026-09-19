# 小米背屏开关 · Xiaomi BackScreen Switch

通过 [Shizuku](https://shizuku.rikka.app/) 开启、唤醒和关闭小米手机背屏的小工具，使用 Kotlin、Jetpack Compose 和 Material 3，无需 root。

- 一键关闭背屏，或开启并唤醒背屏。
- 区分“已关闭”“已开启但休眠”和“亮屏”等状态。
- 默认操作 Display 1，可在设置中修改；不允许操作主屏 0。
- 跟随系统深浅色和动态配色，支持 Android 主题图标。
- 前台静默刷新状态，开关操作完成后再次确认结果。

## 使用

从 [Releases](https://github.com/cr-zhichen/xiaomi-backscreen-switch/releases) 下载 APK。带 `Pre-release` 标记的是测试版本。

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

## CI 与发版

- 推送 `main`、提交 PR 或手动运行 CI：检查工作流、运行 Lint 和控制逻辑测试，并验证调试版构建；不发版。
- 推送新的 `v*` Tag：检查发布版、构建并校验签名 APK，上传 APK 和 SHA-256 校验文件到 GitHub Release。
- `v1.0.2` 发布正式版；`v1.0.2-beat.1`、`v1.0.2-beta.1`、`v1.0.2-rc.1` 等带 `-` 的版本发布为预发布，不替换正式版的 Latest。

例如发布测试版：

```bash
git tag v1.0.2-beat.1
git push origin v1.0.2-beat.1
```

Tag 必须是 `v主版本.次版本.修订版本`，可以附加预发布后缀。APK 的 `versionName` 取 Tag 去掉 `v` 后的内容，`versionCode` 为 `1000 + Release 工作流运行编号`，后续运行按编号递增。发布失败可在 Actions 中重试原运行，无需移动已有 Tag。

### 发布签名

仓库中的 `signing/release.p12` 是密码加密的 PKCS#12 密钥库，包含专用 RSA-4096 发布密钥。私钥和证书使用 AES-256 加密、PBKDF2-HMAC-SHA256 派生，迭代 600,000 次。密码为本地生成的 384 位随机值，在 GitHub 的 `release` Environment 中保存为 `ANDROID_KEYSTORE_PASSWORD` Secret；发布流程只在签名构建步骤注入该密码。

`release` Environment 仅允许 `v*` 标签部署。证书的公开 SHA-256 指纹保存在 `signing/certificate-sha256.txt`，每次构建都会验证 APK 签名、包名、版本号和不可调试属性。APK 直接上传到 Release，不额外保留 Actions artifact。

维护者的本地密码备份位于 `.signing/release-password`，权限为 `600`，已被 Git 忽略。请将它单独备份到密码管理器；GitHub Secrets 无法取回原始值。不要重新生成已发布应用的签名密钥。

本地验证发布构建（不会上传或创建 Tag）：

```bash
export ANDROID_KEYSTORE_PASSWORD="$(cat .signing/release-password)"
RELEASE_TAG=v1.0.2-beat.1 BUILD_NUMBER=1 mise run release
unset ANDROID_KEYSTORE_PASSWORD
```

产物位于 `dist/`。`mise run signing:generate-release` 仅用于首次初始化签名，检测到现有证书或密码时会拒绝覆盖。

发布版和此前手动安装的调试版签名不同，首次切换需要卸载调试版，再安装 Release APK；之后发布版之间可正常覆盖更新。

## 项目结构

```text
app/src/main/java/…/backscreen/   原生界面、状态管理与 Shizuku 服务
app/src/main/aidl/               受限的显示器控制接口
app/src/main/res/                图标与主题资源
app/src/test/                   控制逻辑回归测试
mise.toml                       工具版本与开发任务
.github/workflows/              CI 检查与 Tag 发版
scripts/                        签名初始化和发布产物校验
signing/                        加密发布密钥库与公开证书指纹
```

## 许可证

项目采用 [MIT License](LICENSE)。第三方依赖与图标的许可说明见 [NOTICE.md](NOTICE.md)。
