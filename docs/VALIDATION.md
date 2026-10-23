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
Mesa/ZinkのAFV表示フレーム接続はフレーム色画像の往復とGL後処理まで実装。Irisとの同時動作は未実装。
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

## CPU画像資源のGL登録と実サンプリング

同じLinux / RTX 3060環境で、CPUからの画像入力をVulkanと実Zinkへ転送する経路を追加。
これは各API側に資源を持つ入力経路であり、直接GLで変更した画像やGPU描画先の
双方向共有を実装したものではない。

- `verifyTextureUpload`：RGBA、RGBの行padding、BGRAとrow/skip、R8 alignment、
  signed byte、packed 8_8_8_8/REV、入力サイズとoverflowの回帰検査が通過。
- 実機の私有2×2画像でBGRAの行読み飛ばしとPBOのbyte offsetをVulkan側で照合。
  packed RGBA/BGRAの通常/REVの4通りは、実GLドライバーとVulkanの両方で照合。
  `AFV_TEXTURE_DUAL_UPLOAD_PASS` を記録。テスト準備中だけの数値readbackであり、
  製品の画像輸送経路には使用しない。
- MinecraftのテクスチャIDが実GL名になり、NativeImage／TextureUtil経由の
  アトラス・フォント・各mip・アニメーション更新をZinkへ転送。
- block atlasの実GL寸法1024×512と継続的なuploadを、title/world/resize/reloadの
  各段階で確認。最終Zink実行ではreload後に134,398回のuploadを記録。
  この回数はアニメーションやフレーム数で変動し、性能目標ではない。
- GLSLがその2×2の入力を実サンプリングして共有画像へ描画。
  画面用バッファの32×32診断領域の4色を1,339フレームで数値照合し、
  `AFV_ZINK_COMPOSITION_PASS` とworld/resize/reload/disconnectの正常終了を記録。
- resize中もGL contextとテクスチャregistryを保持し、旧swapchain aliasのGL名を解放。
  共有フレーム資源だけを再生成し、CPU画像のGL名を無効にしない。
- Luna Mediumで最終build、CPU回帰検査、16依存を含む配布JAR検査が通過。

全画面等のスクリーンショットは取得していない。画素の数値照合は私有テスト画像と
32×32診断領域に限定され、アトラス全体やワールド全体の画像一致は主張しない。
Mesaのcopy-box性能警告は残り、速度・メモリ使用量の最適化は未実施。
Iris/Sodiumの同時導入、render target／depth／MRT／buffer全般の共有は引き続き未達。

同じ最終ソースで `-Dafv.smokeTest=true -Dafv.smokeWorld=true runClient`
（Zink無効）もworld/resize/reload/disconnectまで通過し、終了コード0を確認した。


## Vulkan描画からGL後処理を経由するフレーム表示

共有画像をフレーム寸法に拡張し、実際のMinecraft描画をZinkでサンプリングして
Vulkan表示へ戻す経路に変更。入力画像と出力画像を分け、各方向に外部semaphoreを
使う。最終Vk submitの通常frame fenceで、2段のVk commandとGL処理の完了を保護する。
製品輸送にCPU画像コピーや追加の毎フレームfence waitは使用しない。

同じLinux / RTX 3060 / UNORM swapchainでの追加結果：

| 構成 | 検証した範囲 | 結果 |
| --- | --- | --- |
| Zink、identity GL pass | 実sceneのGPU往復、32×32の入出力RGBA一致、CPU assetの別32×32 sampler検査、world/resize/reload/disconnect | `AFV_ZINK_SCENE_PASS: 1351 scene regions verified, effect=identity`、終了コード0 |
| Zink、invert GL pass | 実GLSLのRGB反転とalpha保持を小領域で照合、同じワールドライフサイクル | `AFV_ZINK_SCENE_PASS: 1339 scene regions verified, effect=invert`、終了コード0 |
| build / 配布JAR | Luna Mediumによるbuild、CPU転送回帰、16依存のJarJar構造 | 通過 |

GL passはnative-bridgeの `GlFramePass` を実装する。実texture/FBOは各呼び出し中だけ
借用し、resize後に保持しない。現在の実行位置はGUIを含むMinecraft描画の後で、
Irisのworld/shadowフックを代替するものではない。sRGB用の画像・GL変換も実装したが、
この実機でそのsurface formatを選んだ起動は未検証。深度、MRT、MSAA、Iris/Sodiumとの
同時動作、他GPU/OSは引き続き未達。

数値readbackは(0,0)の32×32の私有asset診断領域と、(32,0)の32×32のscene比較領域に
限定。フレーム全体のCPU取得・保存・スクリーンショットは実施していない。
上記の小領域検査から、画面全体の画素一致やIrisの完全互換は主張しない。

Zink無効の通常AFVでも同じ最終ソースでworld/resize/reload/disconnectを再実行し、
`AFV_SMOKE_PASS` と終了コード0を確認した。
