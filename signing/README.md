# ChroStar 签名密钥

| 项 | 值 |
|---|---|
| 文件 | `chrostar.keystore`（PKCS12，2.6 KB） |
| 别名 | `androiddebugkey` |
| 库口令 / 密钥口令 | `android` |
| 类型 | 由 AGP 调试密钥转换而来（v2/v3 签名，RSA 2048，有效期约 30 年） |
| 证书 SHA-256 | `2b1e6c21f8280f37669f48403e6677479fdbf0110e768fb06752a8425259b65f` |
| 文件 SHA-256 | `ea43c897b53feff9836f12a71608cb96703d61abaf192b43b5d9a9427b309691` |

构建时会自动读取 `keystore.properties`；文件不存在则回落到 AGP 默认调试密钥
（签名会变，已安装的旧版本将无法覆盖升级）。

核对 APK 用的确实是这把钥匙：

```bash
apksigner verify --print-certs ChroStar-*.apk | grep SHA-256
# 应输出 2b1e6c21f8280f37669f48403e6677479fdbf0110e768fb06752a8425259b65f
```

## ⚠️ 这个密钥是公开的

本仓库为公开仓库，密钥与口令都随仓库发布。这意味着：

- **任何人都能构建出与官方 Release 签名一致的 APK**，Android 会把它当作正常更新。
- 请只从本仓库的 [Releases](https://github.com/Bcorw/ChroStar/releases) 下载安装；
  第三方转发的 APK 无法通过签名辨别真伪。
- 该密钥只用于 ChroStar，不要再用它给其它要分发的应用签名（签名即身份，一损俱损）。

## 为什么还要放进来

ChroStar 需要跟随 Chrome 版本反复适配（156 → 158 → …）。签名一旦变化，已安装用户
必须"卸载 → 重装 → 重新在 LSPosed 勾选作用域"，配置也会丢。把密钥纳入仓库后，
任何机器 / 任何一次重建都能产出同一签名，`adb install -r` 直接覆盖升级。

## 轮换（如果哪天需要换密钥）

公开密钥无法真正保密，若担心被伪造，可以换一把新钥匙，代价是所有用户重装一次：

```bash
keytool -genkeypair -v -keystore chrostar-new.keystore -alias chrostar \
  -keyalg RSA -keysize 2048 -validity 14600 \
  -storepass <新口令> -keypass <新口令> \
  -dname "CN=ChroStar, O=Bcorw, C=CN"
```

然后更新 `keystore.properties`、重新发版，并在 Release 说明里提示"需卸载旧版再安装"。
