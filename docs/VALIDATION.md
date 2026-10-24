# 初期移植の検証記録

日付: 2026-10-24。対象: AFV 0.1.0-alpha.1 / Minecraft 1.21.1 / NeoForge 21.1.228。

実装の進行順に追記した記録。各節の「未実装／未達」はその時点の状態を示す。
最新のZink GLクライアントとAFV Vulkan表示の接続結果は末尾を参照。

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


## 2026-10-24: ワールド深度、HUD、設定と別Xセッション

以下が現在の実装の検証結果。上のフレーム合成節は、その時点の履歴である。
Linux / RTX 3060 / Xephyr :97、NeoForge 21.1.228で実施した。

| 構成 | 結果 |
| --- | --- |
| 通常AFV、投影行列修正後 | world/resize/reload/disconnect、終了コード0。クライアントだけの画像でHUD・照準・通知・手元を確認 |
| AFV＋Zink、identity | ワールド終了段階844フレーム、小領域1092回一致、終了コード0。HUDとワールドの実画面も確認 |
| AFV＋Zink、D24S8 depth | 手元の深度クリア前のワールド826フレーム、深度可視化の小領域1059回一致、同じライフサイクルで終了コード0 |
| OpenGL明示 | AFV描画Mixinを適用せず通常GLでworld/resize/reload/disconnect、終了コード0 |
| 設定画面 | OpenGL上で実表示、Auto/Vulkan/OpenGL、保存、戻る、現在の描画方式を配置。画面の保持と正常終了を確認 |
| Vulkan loader不在の注入 | 存在しないライブラリパスでUnsatisfiedLinkErrorを捕捉、OpenGLへ復帰。タイトル・実GLアトラスと正常終了を確認 |
| 中断起動の復旧 | 専用インスタンスに作った試験用startup markerを検出してOpenGLへ復帰、タイトル・正常終了を確認 |
| 機械検査 | Luna MediumによるbuildとProjection/Texture回帰が通過。その後の環境調整は別途最終検査する |

JOMLのMatrix4f MixinはNeoForgeのbootstrapクラスに適用されないため、Vulkanへ渡す
投影行列の境界でGLの[-1,1]を[0,1]へ変換した。GUIの奥行きがクリップされていた
HUD欠落を修正。元のGL行列は書き換えない。D24S8の深度可視化比較には浮動小数点と
UNORM量子化による最大1段階の差を許容する。色identityのUNORM比較は完全一致。

キャプチャはタイトル/ID/現在の境界を確認したMinecraftクライアントか設定パネルに限定。
仮想デスクトップ全体は撮っていない。別Xセッションでも対象をクライアントに限定した。画面全体の画素一致は主張しない。

FPS計測用に固定seed/カメラ、warmup後のRenderFrameEvent.Post間隔とp95/p99を保存する
ハーネスを追加した。:97の通常GLはllvmpipe、VulkanはRTX 3060となったため、得られた
FPSをVulkanの改善率として比較しない。性能条件の一致と実DTFuelでの性能評価は未達。

### DTFuel専用コピー

`/mnt/hdd/MinecraftModpacks/DTFuel`（0.1.10）の204 MOD（984,847,996 bytes）を
`build/compatibility/dtfuel-client`へ独立コピーし、すべてSHA-256で照合した。
NeoForge 21.1.251 / Iris 1.8.14-beta.1 / Sodium 0.8.13 / Sodium Extra 0.9.4。
元の環境に設定変更、MOD追加削除、ワールド書込みは行っていない。

初回の実起動はAFVがOpenGLを選択した後、CocoaInputのXIM初期化でネイティブSIGSEGV
（XIMOfIC、XIC=null）になった。別XセッションのXIM設定と実GPUでのGL環境を調整中。
まだDTFuelが正常動作したとは扱わず、Vulkan上のIris完全互換も未達のままとする。


DTFuelの配布JARによる通常NeoForge起動では、`XMODIFIERS=@im=none`と実Mesa EGL/Zink
（GL 4.6 / RTX 3060）で初回の入力クラッシュを解消した。開発起動では別途GameTestの
任意依存クラス参照とJadeの開発時assertionに遭遇したため、インストール済みの公開
launcher metadataを用いた通常起動へ変更。2026-10-24 12:58 JSTに以下を通過した：

- 元の204 MOD＋配布AFV JAR、NeoForge 21.1.251。
- タイトル、8192×8192 block atlas、専用の新規flatワールド参加、960×540リサイズ。
- ワールド中のリソース再読込、切断、タイトル復帰、正常終了。
- `AFV_SMOKE_PASS: scenario=world-resize-reload-disconnect` と
  `AFV_PRODUCTION_PASS: exit=0`。resourcepack fallbackなし。
- 記録: `build/reports/afv-validation/dtfuel-production-baseline.log`（ローカルのみ）。

この検証ではAFVの描画方式はOpenGLで、実GLドライバーがZinkによりVulkanを使用する。
AFV本来のVulkan描画とIrisを統合した実績ではない。元のIris設定はshaderPack未選択で、
Irisシェーダーパック有効時の追加検証と意味のあるFPS比較はまだ残る。モデル／レシピ等の
pack側の警告は残り、各MODの全機能が正しいという証明にはしない。


## 指定Complementary Reimagined r5.9.3の実機検証

ユーザー指定ZIPと隣接`.zip.txt`を専用コピーで使用した。
ZIP SHA-256: `fed6c879e732cf7fc7bc6570ebb1def43235aa7e96f62859fbdbdbfa0b484b38`。
元の204 MODとコピーのJARを元manifestのSHA-256で再照合し、元ZIPの一致と
9項目の設定値保持も確認。Irisはコピー側のコメント日時を書き直すが、値は同一。

最初のEGL/Zink起動はpipelineとライフサイクル検査が通っていても画面が黒かった。
その時の86.9 FPSは表示の成功や高速化の実績として使用しない。シェーダーなしの
設定画面でも再現し、Xephyrでの表示経路に切り分けた。検証用に
`LIBGL_KOPPER_DRI2=true`を選ぶと実表示が回復した。これは
[MesaのX11互換設定](https://docs.mesa3d.org/envvars.html#libgl-kopper-dri2)を使った
この環境の結果であり、他環境に同じ設定が必要とは主張しない。
EGL用のGL関数取得もeglGetProcAddressに統一した。

最終構成: DTFuel 0.1.10の204 MOD＋配布AFV JAR、NeoForge 21.1.251、
Iris 1.8.14-beta.1 / Sodium 0.8.13、RTX 3060 / 実Zink GL 4.6、Xephyr :97。
AFVのnative Vulkan rendererは無効のGL→Zink比較経路。

- 指定Complementaryが選択され、ワールド／リサイズ後／resource reload後に
  実`IrisRenderingPipeline`、pack有効、stored shader errorなしを確認。
- Minecraftのcolor textureの中央32×32を数値確認。ワールドで1024/1024画素が非黒。
- 対象Minecraftクライアントだけの960×540画像で、雲・地形・動物・手元・HUD・
  JourneyMapを視認。仮想デスクトップ全体は撮っていない。
- 実ウィンドウを960×540→854×480→960×540へ変更し、寸法を各段階で確認。
  その後の再読込、pipeline有効、切断、タイトル復帰、client exit=0が通過。
- ハーネスも、起動時960×540の場合に別サイズへ変更するよう修正した。
- `AFV_SMOKE_PASS`、`AFV_PRODUCTION_PASS: exit=0`、外側の検証プロセスも終了コード0。
- `build compileBaselineJava`、Projection/Texture回帰、Luna Mediumの成果物／Python検査が通過。

短時間のflatワールド計測（固定seed、camera 0.5/-60/0.5）：

| 条件・指標 | 値 |
| --- | --- |
| 解像度 / 描画距離 / simulation | 960×540 / 7 / 6 |
| vsync設定 / FPS上限設定 | true / 120 |
| サンプル | 580 frame intervals、14.965秒 |
| 平均FPS | 38.76 |
| median / p95 / p99 frame interval | 24.04 / 35.83 / 63.52 ms |

RenderFrameEvent.Post間隔で、pacingと表示を含む。GPUのみの処理時間ではない。
X11互換表示の回避設定を含む短い一回の測定で、通常GLと同一条件の比較ではなく、
改善率や実運用全般のFPSとして扱わない。Shaderの全効果・複数の通常地形・
AFV本来のVulkan描画とのIris統合は未達。pack側の既知のモデル／レシピ警告も残る。

## Iris GLクライアントとAFV Vulkan表示の接続（2026-10-24、f2e44ef）

既存のIris/Sodium描画を実Zinkで動かし、その最終色画像をAFV所有のVkDeviceと
swapchainへGPU共有する経路を追加した。`--zink`の通常GL/EGL比較とは異なり、
この経路はAFVがVulkanのsurface・device・swapchain・表示と画像所有権を管理する。
画面全体をCPUへ読み戻す経路は使用しない。AFV native地形描画とは別モードであり、
native地形へ任意のIris passを差し込む実装の完成を意味しない。

起動設定の「Vulkan」とIris/Sodiumの検出から選択する。配布JARの親ローダーにある
GLFWと、子ローダーのVulkan binding間で生じた`VkInstance`のクラス解決失敗を修正し、
surface生成時はネイティブhandleを渡す。表示専用経路はnative terrain/VMAを初期化せず、
graphics/present queueのみを要求する。Vulkan feature照会のpNext終端もゼロ初期化した。

起動前には独立したJVMで実Zink GL 4.6、computeの画素、GPU UUID一致、外部画像と
双方向セマフォの実往復を検査する。無効なEGL vendorライブラリーを指定した負例は
子JVMが終了コード1となり、親JVMは生存して非対応判定を返した
（`AFV_ZINK_PREFLIGHT_FAILURE_ISOLATED`）。同じ無効vendorで実Minecraftクライアントも
OpenGLを選び、アトラス・非黒のタイトル画像・`AFV_SMOKE_PASS`・終了コード0を確認した。
タイムアウトもOpenGL選択へ戻す実装。
これは起動前の検査であり、ゲーム中のドライバー障害を無事故と保証しない。

### 指定ComplementaryとDTFuel

元の204 MODを独立コピーした同じDTFuel、NeoForge 21.1.251、Iris 1.8.14-beta.1、
Sodium 0.8.13、RTX 3060 / Mesa 25.2.8 / NVIDIA 595.99.02、専用Xephyr :97を使用。
指定ZIPのSHA-256は上記と同一で、隣接TXTの9設定値も保持する。
起動時のVulkan選択は、vanilla強制用`afv.zinkClient`を使用せず設定と同じ分岐を検証した。

- 実`IrisRenderingPipeline`、pack有効、stored shader errorなしをワールド・リサイズ後・
  リソース再読込後に確認。通常GLへのfallbackを合格にしない。
- 元のflat worldに加え、新規の検証ワールドへ石の柱、ガラス、葉、水、発光ブロック、
  cow、itemを配置。時刻1000、天候固定で確認する。
- Minecraft viewportだけをキャプチャし、シェーダー付きのワールド、影、水、透過、
  手元、HUD、ミニマップがAFVのVulkan表示に出ることを目視確認。
- GPU共有フレーム120回以上を必須とし、先行シーン試験は8490回の実Vulkan表示と
  world/resize/reload/disconnectを通過し終了コード0。最後の配布JARでは9093回の実Vulkan表示、
  同じライフサイクル、クライアントと起動スクリプトの終了コード0を確認。
- 元MOD204本とコピー204本のSHA-256計408件、指定ZIPの同一性、9設定の意味値を照合。

起動中の`glTexSubImage2D(invalid texture level 0)`は、AFVのVulkan表示を無効にした
通常Zink/EGL比較でも再現し、Railwaysの`PhantomSpriteManager.renderTick`から出ることを
同期GL debugで確認した。producerが残したGL errorをAFVの外部画像転送失敗として
扱っていた点を修正した。AFV自身の色転送・外部同期のエラー検査は残す。
このpack側エラー、既存のモデル・レシピ・registry警告は未解消であり、全MOD機能が
無警告で正常という判定にはしない。

### 同じZink描画を使う表示経路の参考計測

同じ配布JAR、scene、ZIP/TXT、960×540、描画距離7・simulation 6、VSync無効、
frame limit 260（Minecraftの無制限設定）、focusあり。world安定120 tickに加え
900 tick待機し、600 tickを計測。両方とも同じエラー限定GL debugを有効にした。
frame間隔は`RenderFrameEvent.Post`で取得し、表示・待機を含む。

| 表示経路 | frame数 / 秒 | 平均FPS | 中央値ms | p95 ms | p99 ms |
| --- | --- | --- | --- | --- | --- |
| 通常Zink/EGL、nested X11用`LIBGL_KOPPER_DRI2=true` | 1374 / 29.970 | 45.85 | 21.53 | 24.46 | 27.42 |
| Zink GL client → AFV Vulkan WSI | 1373 / 29.979 | 45.80 | 21.44 | 26.40 | 29.10 |

平均は約0.1%差でほぼ同程度。p95/p99はこのAFV試行の方が大きい。各30秒の単発試行で、
実行時刻も異なるため有意な改善・悪化とは判断しない。これは表示経路の比較で、
NVIDIAの通常OpenGLドライバーに対する高速化の証拠ではない。上記の38.76 FPSとは
scene・VSync・待機条件が異なるため比較しない。Iris全機能、他shader pack、全GPU/OS、
全context形式、native地形へのIris統合は引き続き未達。

詳細ログ・JSON・スコープ限定画像はローカル`build/reports/afv-validation/`の
`dtfuel-gl-scene-final*`、`dtfuel-afv-scene-final*`、`dtfuel-afv-complementary-scene.png`、
`dtfuel-source-integrity-final.json`に保存する。DTFuelの第三者MOD JARと提供シェーダーZIPは
ローカルの検証にのみ使用する。

同じ最終JavaソースでAFV native描画も回帰確認した。NeoForge 21.1.228の開発クライアントで
タイトル、60029回の地形draw、新規ワールド、960×540へのリサイズ、リソース再読込、
切断を通過し、`AFV_SMOKE_PASS`と終了コード0を確認した
（`native-regression-final.log`）。ビルド、投影変換、texture upload、baselineのコンパイル、
配布JAR構造とPython構文の機械検証も通過している。

ローカル証跡は `build/reports/afv-validation/dtfuel-complementary-final-*` と
`dtfuel-complementary.png`。元のmodpack／shaderファイルは変更していない。

設定切替は、実UIでOpenGLを選んで保存し、次回起動が保存済みOpenGLを選ぶことを確認。
検証後は開発インスタンスだけをAUTOへ戻した。通常のVulkan起動の設定画面も実表示済み。

## GL最終出力の保持と自動選択（2026-10-24）

優先順位を、既存MODのGL呼び出し・描画順序・状態・資源寿命を保持する変換へ合わせた。
独自の描画変更や追加最適化は後の任意featureとし、ここでは基礎的な互換境界を修正した。
第三者MODがある場合は未知のMODも含め、Auto/Vulkanから実Zinkの既存GL frontendを選び、
AFV native地形フックによる置換を避ける。実機の起動前検査が失敗すればOpenGLへ戻る。

- 表示元をmain color textureから実default framebufferへ変更。Irisの最終window passや、
  他MODが画面へ直接描いた内容を含めてVulkanへ渡す。
- EGL drawableに実ウィンドウと同じ大きさ、depth 24 / stencil 8を用意する。
  リサイズは次のフレームの描画前に行い、同じGL contextと既存texture/FBO、viewport、
  scissorを維持する。描画済みの出力をリサイズで破棄しない。
- 別JVMの起動前検査を拡張し、非表示・非focusのNO_APIウィンドウで同じGPUの
  実surface、graphics/present queue、transfer/blit形式、共有形式のFD import、
  論理deviceとswapchain作成まで確認する。子JVMの失敗・異常終了・タイムアウトは
  OpenGL選択へ戻す。検査用GLFWはprobe JAR内に隔離し、ゲームのGLFWを置き換えない。

単体probeでは73×41→31×47のdrawable変更を通じて、生きたtexture/FBOの内容、
GL状態、corner pixels、depth/stencilを確認。外部画像・双方向セマフォの検査に加え、
同じGPUで3 imageの実swapchain作成を通過した。
`default-framebuffer-wsi-probe.log`に4種類の成功markerを保存している。

vanilla実クライアントでは3021回の実Vulkan表示、3018回の最終出力marker照合、
world・リサイズ・再読込・切断・タイトル復帰と終了コード0を確認した
（`default-framebuffer-client.log`）。検査markerはmain textureを変更せず、全GL描画の後に
default framebufferへ直接描く8×8のmagenta領域。その画素を共有Vulkan imageから
slot fence後に照合する。読み戻しは明示した検査flag時だけ行う。

現在のビルド、baselineのコンパイル、投影・texture upload検査、配布JAR構造、
launcherのPython構文と差分の空白検査を通過。

専用Xの約45.8 FPSはユーザーの通常環境の約80 FPSと条件が一致せず、性能の基準には
しない。この実装パスではFPSを再計測せず、互換性とライフサイクルを確認した。
実用性能の検証はユーザー側の条件を優先する。

DTFuelの元204 MODを保持した独立コピー、NeoForge 21.1.251、Iris 1.8.14-beta.1、
Sodium 0.8.13と指定Complementary ZIP/TXTで実クライアントを再確認した。
強制flagなしの`requested=AUTO, active=Vulkan`を確認し、5590回の実Vulkan表示と
5587回の最終出力marker照合を通過。960×540→854×480のリサイズ、
リソース再読込後も実`IrisRenderingPipeline`が有効で、切断・タイトル復帰を含む
`AFV_SMOKE_PASS`、クライアントとlauncherの終了コード0を確認した。
viewport限定画像で影・水・透過・葉・発光ブロック・手元・HUD・ミニマップを確認した。
右上の小さなmagenta領域は最終出力を調べるための検査markerで、通常起動では描かない。
今回もpack由来の既知のGL producer errorは記録されており、全MODの全機能が
無警告という意味ではない。

証跡はローカル`build/reports/afv-validation/`の`dtfuel-default-output-*`と
`dtfuel-default-output.png`。提供MODやshaderのバイナリーを公開リポジトリーへ含めない。

## Indexed GL状態の保持（2026-10-24）

表示後の`GlStateScope`が、描画先0の色マスクとblend enableをlegacy APIで
復元していたため、他の描画先の独立した設定を上書きする問題を修正した。
viewportとscissorも全indexを保存し、小数座標を含めてindexed APIで復元する。
EGL drawable変更時も同じ境界を使い、resizeで他indexを失わないようにした。
これは独自の最適化ではなく、既存MODが設定したGL状態を保持するための修正。

legacy APIが全indexへ影響する仕様は[Khronosの色マスク資料](https://wikis.khronos.org/opengl/Color_Mask)と
[ARB_viewport_array仕様](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_viewport_array.txt)を参照。

起動前probeに、異なる色マスク・blend enableを持つ2つのMRT attachmentと、
各indexで異なるfractional viewport／scissorを設定する実GPU検査を追加した。
legacy APIによる全index上書き、入れ子scope、例外終了、2回のdrawable変更を経ても
状態が維持され、MRTの結果がRGBA (255,0,255,255) と (0,64,191,255) に
なることを確認する。単に保存値が一致するだけでなく、次の描画結果を検査する。
この成功markerも、ゲームがZink経路を有効にする前の必須条件にした。

実RTX 3060 / Mesa Zinkで、全描画先と16 viewport／scissor、MRTの画素検査、
従来のdefault drawable／外部画像／WSI検査が通過した。ビルド、baselineのコンパイル、
配布JAR構造検査と差分の空白検査も通過。ログはローカル
`build/reports/afv-validation/indexed-state-build.log`と`indexed-state-probe.log`。

修正後の配布JARを同じDTFuel独立コピーへ入れ、指定Complementaryを再確認した。
`AUTO`選択から実Vulkan表示4516回、最終default出力のVulkan画像照合4513回を通過。
ワールド、960×540→854×480のresize、リソース再読込後まで実Iris pipelineが有効で、
切断・タイトル復帰・clientとlauncherの終了コード0を確認した。性能計測は行っていない。
pack由来の既知のproducer GL警告は引き続き残る。証跡はローカル
`build/reports/afv-validation/dtfuel-indexed-state-*`。全shader packや全MOD機能の
完全互換を証明するものではなく、今回はこの状態保持の不具合と回帰を扱った。

## Irisの有効・無効切替とpipeline再生成（2026-10-24）

同じワールド内のIris再構築を継続的に検査できるよう、明示的な`--iris-cycle`を
専用クライアントのハーネスへ追加した。通常起動では実行しない。Irisの公開
`IrisApiConfig.setShadersEnabledAndApply`を使い、実際の保存・再構築を通す。
その後のshader-only reloadは実`Iris.reload`を呼ぶ。

- シェーダーを無効化し、同じワールドで854×480→960×540へ再リサイズする。
- 60 client tickと60以上の実表示frameを待ち、実`VanillaRenderingPipeline`、
  pack無効、stored errorなし、非黒の画像を確認する。
- 同じpackを再有効化し、再び実描画を待つ。実`IrisRenderingPipeline`が初回の
  objectとは異なり、選択packが維持され、stored errorがないことを確認する。
- shader-only reload後も別の実pipelineと非黒画像を確認する。成功markerは
  `AFV_IRIS_CYCLE_PASS`で、launcherもこのmarkerを必須にする。
- 続けて既存のリソースpack再読込・切断・タイトル復帰まで確認する。

DTFuelの独立コピー、元の204 MOD、指定Complementary ZIPと9設定値を使い、
AUTOからAFV所有のVulkan表示へ入る同じ構成で実施した。Iris 1.8.14-beta.1 /
Sodium 0.8.13 / NeoForge 21.1.251 / RTX 3060 / 専用Xephyr環境。
シェーダー切替・再リサイズ・再有効化・shader-only reloadは通過し、
提供ZIPの同一性と9設定値の保持も確認した。FPSは測定していない。

最後のリソース再読込後も実Iris pipelineが有効で、4333回の実Vulkan表示と
4329回の最終default出力のVulkan画像照合、切断・タイトル復帰・clientとlauncherの
終了コード0を確認した。今回の再構築シーケンスでは追加のrenderer不具合は再現しなかった。
pack由来の既知のproducer GL警告は残り、他のpack・全設定・全MOD機能の完全互換を
証明するものではない。ビルド、baselineのコンパイル、配布JAR構造とPython構文の
機械検査も通過。証跡はローカル`build/reports/afv-validation/`の
`iris-cycle-build.log`と`dtfuel-iris-cycle-*`。元のmodpack・提供shaderは変更していない。

## 表示面の休止・復帰と対応形式の選択（2026-10-24）

IrisのGL frontendを保持する表示経路で、hardware／window system差に関わる基礎を修正した。
最初の8-bit surface formatを選んだ後でblit非対応として失敗する処理を、全候補から
blit先・外部共有元の両方が使える組を選ぶ共通policyへ置き換えた。UNORMを優先し、
sRGBが必要な場合は共有元もsRGBにそろえる。色空間の解釈を変更する機能は追加しない。
起動前の子JVM検査と本番のpresenterで同じ`PresentationSurface`を使用する。

GLFWのサイズが非0でも、最小化時にsurfaceのcurrent/max extentが0になる場合がある
（[Vulkan仕様のswapchain条件](https://docs.vulkan.org/refpages/latest/refpages/source/VkSwapchainCreateInfoKHR.html)）。
この場合は既存画像を破棄せず表示を休止し、復帰後に再照会してswapchainを作り直す。
GLFWのiconified状態も明示的に休止対象にした。image acquireの無期限待ちを100 msの
上限へ変更し、TIMEOUT／NOT_READYなら同期objectを再利用できる状態のまま次のframeへ
戻る。作成・取得時のOUT_OF_DATEも次のframeで再試行する。

CPU検査では、GLFWサイズとsurfaceの不一致、current/max extentが0、復帰後の正サイズ、
可変サイズのclamp、非正当なlimits、最初の形式のblit非対応、外部共有非対応、
sRGB形式への代替、未対応の色空間を扱った。`AFV_PRESENTATION_SURFACE_PASS`を
通常のcheckへ接続し、ビルド・baselineコンパイルとともに通過した。実RTX 3060の
外部画像・WSIを含む従来5種類のGPU probe、配布JARとPython構文検査も通過。
ログはローカル`build/reports/afv-validation/surface-lifecycle-build.log`と
`surface-lifecycle-probe.log`。他GPUのcapability組合せの検査はCPUでのpolicy検査であり、
未所有GPUの実機確認とは区別する。

専用クライアントに`--window-cycle`を追加した。実window managerへ最小化を要求し、
実iconified状態を検出した後、20 client tickにわたりAFVのVulkan表示回数が増えない
ことを確認する。復帰とVSyncの切替・復元の各段階で60 tick／60 frame以上の描画、
同じEGL context、非黒の画像と実Iris pipelineを要求する。window managerがない場合は
試験失敗とし、最小化を模擬して合格にはしない。launcherにも成功markerを必須にした。

実運用packの独立コピー（元204 MOD、NeoForge 21.1.251、Iris 1.8.14-beta.1、
Sodium 0.8.13）と指定Complementaryを、専用Xephyr :97＋Metacity（compositing無効）で
起動した。AUTO→Vulkanを確認し、Irisの無効化／再有効化／shader-only reload、
2回のresize、リソース再読込を通過。実iconify中に20 tick処理でき、Vulkan表示回数が
増えないことを確認した。復帰後とVSync false→true→falseの各段階で実Iris pipelineと
非黒画像、Vulkan表示再開、同じEGL contextを確認し、`AFV_WINDOW_CYCLE_PASS`を通過。
実機の最小化はiconified分岐を検証し、surface extentが0になる条件は上記CPU検査で
扱った。この区別を保ち、全window systemで0 extentを実測したとはしない。

最終的に4582回の実Vulkan表示、4578回の最終default出力のVulkan画像照合、
切断・タイトル復帰・clientとlauncherの終了コード0を確認した。FPSは測定していない。
pack由来の既知のproducer GL警告は残る。証跡はローカル
`build/reports/afv-validation/dtfuel-surface-lifecycle-*`に保存した。
元のmodpackと提供shaderは変更せず、第三者のバイナリーは公開しない。
