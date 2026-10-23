# 初期移植の検証記録

日付: 2026-10-24。対象: AFV 0.1.0-alpha.1 / Minecraft 1.21.1 / NeoForge 21.1.228。

## ビルドと配布構造

完全なEclipse Temurin JDK 21で `./gradlew build` が通過。
Java 21 JREのみの環境ではMinecraftの再コンパイルができないため、JDKが必要。
Luna Mediumによる機械検証で、活動中のJavaソースにFabricクラスへの依存がないこと、
NeoForgeメタデータ・Mixin設定・16個のネストされた依存JARの整合性を確認した。

`python3 scripts/verify_artifact.py` で、MOD ID、バージョン展開、起動クラス、描画本体、
Mixinクラス、ライセンス、共通API、ネイティブJAR、重複しないJarJar IDとJPMS記述子の
除去を検証する。これはOS別の実機起動を保証するテストではない。

## 実行環境

- Linux amd64、X11
- NVIDIA GeForce RTX 3060、NVIDIAドライバー 595.99.02
- LWJGL 3.3.3、Java 21
- Vulkanデバイス生成、内部シェーダーのコンパイル、NeoForgeのMODロードを確認
- `./gradlew -Dafv.smokeTest=true runClient` が正常終了（終了コード0）
- `AFV_SMOKE_PASS: title screen rendered for 60 ticks, framebuffer 854x480` を記録
- リソース読込完了後のタイトル画面で60 client tick継続し、通常の終了処理を実行
- 最終実行のゲームログにERROR/FATALなし（一般的な警告は残る）

実行結果は初期移植の開発クライアントで確認した。ゲーム画面のスクリーンショットは
取得していないため、タイトル画面への到達とフレーム進行はログによる検証である。
画面のピクセル比較・他OS/GPU・配布JARを実ランチャーに入れた起動は
別の検証が必要。ワールド等の追加実機結果は下の表に記録する。

## 起動で修正した移植点

- NeoForgeの変更後のウィンドウ生成経路とシェーダーコンストラクター。
- GLFW/設定の初期化スレッド、開発時のcore-api読み込み。
- LWJGLの拡張列挙に必要なスタックサイズ。
- NeoForgeのリソースローダーに対応したshader include読み込み。
- shadercのinclude結果をreleaseコールバックまで保持し、コンパイル結果を解放。
- NeoForge側で追加された引数を持つパーティクル描画フック。
- ブートストラップ層のLWJGL置換に頼らない、Minecraft内部のFBO破棄等の接続。

## 未達の項目

M0全体の合格宣言はまだ行わない。描画のピクセル比較が必要。
Mesa/ZinkのAFV表示フレーム接続は32×32診断合成まで実装。Irisとの同時動作は未実装。
直接LWJGL/JNIを呼ぶMODの包括的な捕捉も未実装。

## 追加の実機検証（2026-10-24）

いずれもLinux x64 / RTX 3060 / NVIDIA 595.99.02 / JDK 21で実行。
全画面・アプリ画面のスクリーンショットは取得していない。

| 構成 | 確認した範囲 | 結果 |
| --- | --- | --- |
| AFV単体 | 新規flat world、地形の実Vk indexed draw、960x540 resize、resource reload、切断、titleへ復帰 | `AFV_SMOKE_PASS: scenario=world-resize-reload-disconnect`、終了コード0 |
| 独立したZink EGL | GL 4.6 core / GLSL 4.60、compute image-storeの16x16全画素 | `AFV_ZINK_PROBE_PASS`、終了コード0 |
| 別VkDeviceとの実画像共有 | GPU UUID一致、RGBA8 optimal外部メモリFD、双方向semaphore、EXTERNAL queue ownership、16x16と31x31各8往復 | `AFV_EXTERNAL_IMAGE_PASS`、全比較画素一致 |
| Iris 1.8.12 + Sodium 0.6.13、通常GL（AFVなし） | 自作AFV-Smoke pack、実IrisRenderingPipeline、world/resize/shader reload/disconnect | `AFV_IRIS_BASELINE_PASS`、終了コード0 |
| 同じIris + Sodium、実Zink（AFVなし） | GL rendererがZinkであることを照合し、同じシナリオ | `AFV_IRIS_BASELINE_PASS`、終了コード0 |

Irisはシェーダー無効へのfallbackとstored errorを毎段階で検査。
ただしテスト用packはterrain、textured、shadow、finalの小さな入力に限る。
Irisの全描画機能、市販・公開pack全般、AFVとIrisの同時実行を証明する結果ではない。
NeoForgeは1.21.1で一部1.21の版指定をVersionSupportMatrixで許可するため、
Iris JARのMinecraft版範囲だけから非対応と判定せず、未改変JARを実際に起動した。

Zink側のMesaは25.2.8、rendererは
`zink Vulkan 1.4(NVIDIA GeForce RTX 3060 (NVIDIA_PROPRIETARY))`。
共有画像の輸送にはCPUコピーを使わず、数値判定のためだけに私有の小画像をreadbackする。
AFV表示フレームの小領域への組込みは下記の追加試験で扱う。深度/stencil、MSAA等は未検証。
この環境にKhronos validation layerは導入されていないため、validation layerによる
Vulkan仕様検証は未実施。Zink比較には性能警告や通常のshader sampler警告が残る。

描画のピクセル比較は引き続き未実施であり、M0/M1全体の完了宣言ではない。

## AFV本体のVkDeviceとZinkの接続

Linux / RTX 3060で以下を実行。

```sh
./gradlew -Dafv.zinkInterop=true -Dafv.smokeTest=true -Dafv.smokeWorld=true runClient
```

- AFVが既に使用しているVkDeviceで共有画像と外部semaphoreを生成。
- 実Zink GL rendererと同一GPUのUUIDを照合。
- 各フレームの既存フェンス完了後にslotを再利用し、release → GL描画 → acquire →
  swapchain blit → PRESENTを接続。
- swapchainの合成先32×32領域だけを数値検査し、magenta全画素を確認。
  検査用bufferはsmoke test時のみ生成し、通常のフレーム輸送にCPUコピーを用いない。
- worldでの実地形draw、960x540 resize、リソース再読込、切断、title復帰を通過。
- resize時の共有画像・GL import・semaphore・command poolの破棄と再生成も実行。
- 最終実行で `AFV_ZINK_COMPOSITION_PASS: 1325 GPU-composed patches verified`、
  `AFV_SMOKE_PASS: scenario=world-resize-reload-disconnect`、終了コード0を確認。
- フラグなしの通常AFVタイトル起動・正常終了も再検証。
- 配布JARはnative-bridgeとEGLを含む16依存を検査。Luna Mediumの機械検証も通過。

画面全体の画像比較やスクリーンショットは実施していない。数値検査の対象は
自前の診断パッチだけであり、ワールド描画の画素一致・Iris互換を証明するものではない。
Iris/Sodiumの同時導入制限、汎用GL資源経路、深度・MRT・MSAA・実shader pack対応は
引き続き残る。機能は `afv.zinkInterop` の明示指定時だけ有効。
