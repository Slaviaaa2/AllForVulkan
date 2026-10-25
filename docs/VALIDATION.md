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

## Irisのディメンション別pipeline（2026-10-24）

指定Complementary ZIPには`world0`／`world-1`／`world1`に各73本のvertex・fragment・
compute shader入口がある。同じOverworldだけの検査では他ディメンションの経路を
確認できないため、専用クライアントへ明示的な`--dimension-cycle`を追加した。
通常起動では実行しない。

新規`AFV-Smoke-*`ワールド以外を拒否し、実サーバーでOverworld→Nether→End→
Nether→Overworldを移動する。Endから直接Overworldへ戻るcreditsの流れを避け、
各移動先では実際にチャンクが読み込まれたこと、120 tickと120以上の実Vulkan frame、
非黒の色画像、元のEGL contextを要求する。Nether／Endには石・ガラス・発光ブロックの
小さな足場を作り、クリエイティブ飛行で安全に観察する。元のmodpackの既存saveは
使用しない。

各stageでIrisの実dimension IDがクライアントのworld keyと一致することを確認し、
stored shader errorなしの実Iris pipelineを要求する。別ディメンションのpipelineを
使い回していないこともobject identityで検査する。戻った先ではIris自身による
pipeline再利用／再生成の両方を許し、AFVがIris資源を勝手に破棄しない。
選択packは不変で、launcherも`AFV_DIMENSION_CYCLE_PASS`を必須にする。

ビルド、baselineのコンパイル、配布JARと16 nested library、Python構文と差分の
空白検査が通過。機械検査ログはローカル
`build/reports/afv-validation/dimension-cycle-build.log`。

DTFuelの独立コピー（元204 MOD、NeoForge 21.1.251、Iris 1.8.14-beta.1、Sodium 0.8.13）
と指定Complementaryで実施した。AUTO→Vulkan、リサイズ・リソース再読込に続き、
Nether 256 frame、End 239 frame、Nether再訪316 frame、Overworld復帰269 frameを
それぞれ通過し、3種類の独立した実Iris pipelineを確認。5486回の実Vulkan表示、
5483回の最終default出力のVulkan画像照合、切断・タイトル復帰・clientとlauncherの
終了コード0を確認した。FPSの測定は行っていない。追加のrenderer不具合は
このシーケンスでは再現しなかった。pack由来の既知のproducer GL警告は残る。

viewport限定のNether／End画像も取得して一度確認したが、いずれもLoading terrainの
タイミングだった。これは移動先ワールドの目視証拠にはせず、ローカルの
`dtfuel-dimension-loading-*.png`として区別する。上記の判定は移動先の実チャンク、
screenなしでの120 tick待機、GL数値画像検査、IrisとVulkanの実行状態による。
各ディメンションのshader効果全体の目視確認は未実施で、次回の画像取得では
world-ready時点に同期する必要がある。

証跡はローカル`build/reports/afv-validation/dtfuel-dimension-cycle-*`。元のmodpack・
既存save・提供shaderは変更せず、第三者バイナリーは公開しない。

## 検証profileの分離と描画安定後の画像取得（2026-10-24）

最小構成を同じ実運用packから再現できるよう、準備scriptへ`--mod-file`を追加した。
選択した正確なファイル名のJARだけを独立コピーし、packのoverridesは持ち込まない。
manifestには元204本と選択2本を区別して記録する。起動時も全記録JARのSHA-256を
照合し、追加・欠落・改変したMODを拒否する。launcher自身のAFV JARだけを例外にする。
既存のfull profileのコピー動作は保持した。

profileの検査では、元pack内への出力・既存出力・不正／重複／不存在ファイル名の拒否、
最小構成のoverrides除外、非共有コピー、MOD混入・改変・欠落の検出を確認した。
Gradle build、baselineコンパイル、配布JAR、Python構文と2本のprofile unittestが通過。
ログはローカル`profile-inspection-build.log`と`modpack-profile-checks.log`。

元packと同じIris 1.8.14-beta.1 / Sodium 0.8.13の2 JARだけのprofileを新規作成し、
NeoForge 21.1.251＋AFV＋指定Complementaryで起動した。pack固有configは使用しない。
AUTO→実Vulkan表示、シェーダー有効・無効切替、再リサイズ、再有効化、shader-only
reload、リソース再読込、切断、タイトル復帰と正常終了を通過した。
2058回のVulkan表示と2054回の最終出力のVulkan画像照合を確認した。元の2 JARの
SHA-256はDTFuel full profileのものと一致している。証跡はローカル
`build/reports/afv-validation/minimal-iris-sodium-*`。

ディメンション画像用には`--dimension-inspect-ticks`を追加した。実チャンクと
Iris pipelineの数値検査、120 tickと120 frame以上のVulkan表示を通過してから
`AFV_DIMENSION_INSPECT`を出し、現在のworldを一定tick保持する。移動要求に同期して
Loading terrainを撮ってしまった前回の問題を避け、待機後も同じ判定を再実行する。

同じ配布JAR（SHA-256 `4d8677abd101b41745a833e3725e93597a986d2fd2419d6e9f700ce64d1b73e4`）で、
DTFuel full profileの204 JARも起動前照合し、ディメンション検査を200 tick保持で実施した。
Nether・End・Overworld復帰後の各`AFV_DIMENSION_INSPECT`に同期し、その時点の
Minecraft SingleplayerのX11 window ID、title、絶対client boundsを確認してから、
854×480のviewportだけを取得した。別clientがある場合は取得を拒否する。
読み込み画面ではない3画像をそれぞれ一度確認し、ディメンション別の空・霧、
ガラス、発光ブロック、手元、HUD、ミニマップ、およびOverworldの影・葉・水を確認した。
右上の小さなmagenta領域は明示した最終出力検査用で、通常起動では出ない。
shaderの全設定や全効果の正しさを目視したという意味ではない。

検証後も実Iris pipeline、3つのdimension key、同じEGL contextと元pack選択を保持し、
7758回のVulkan表示、7755回の最終出力のVulkan画像照合、切断・タイトル復帰・clientと
launcherの終了コード0を確認した。2 MODの最小構成と204 MODのfull構成の両方が
同じartifactで正常終了している。FPS比較は行っていない。

証跡はローカル`build/reports/afv-validation/dtfuel-dimension-visual-*`、
`dtfuel-validated-dimension-{the_nether,the_end,overworld}.png`と同名の`target.json`、
`dtfuel-dimension-capture.log`。capture待機中にログのtruncateを検知できない問題が
見つかり、取得補助だけを修正して同じ実行中クライアントへ再接続した。ゲームを
再起動して合格にしたものではない。元pack・既存save・提供shaderは変更していない。

## 表示時のGL状態処理の削減（2026-10-24）

互換検証を追加し続ける段階から、既存描画の意味を保つ基本的な改善へ移った。
通常の`ZinkPresenter.present`から全状態の`GlStateScope`を外し、色blitで実際に触る
read/draw FBO、default read buffer、scissor index 0、framebuffer sRGBだけを扱う
`GlBlitScope`を導入した。共有画像のcopy開始はdraw FBOだけをbindし、viewportを
変更しない。画像再作成・EGL resize・診断描画には従来の広いscopeを残す。

今回のGPUの8 draw buffer／16 viewport構成では、scopeの状態照会を1 frameあたり
78呼出しから6呼出しへ減らした。indexed配列の確保、未変更のshader・texture・
viewport・mask・blendの復元も通常表示から取り除いた。これは呼出し数の削減であり、
FPSの改善幅を測定した結果ではない。GPUコピー、所有権移譲、セマフォ同期は維持する。

[OpenGLのblit仕様](https://wikis.khronos.org/opengl/Framebuffer)に従い、viewportや
write maskには触れず、blitに効くscissor index 0だけを一時無効化する。実GPU probeで
異なる全indexed状態を残したまま、default framebufferからprivate FBOへの正確な
RGBAを確認した。default read bufferを`GL_NONE`にした状態、通常終了と例外終了、
scissor／sRGB enableの復元、後続MRT描画の画素一致も通過した。

併せて固定パスだったMesa EGL metadataを自動検出へ変更した。GLVND環境の明示的な
filenames／directoriesを優先し、JSONだけを調べてMesa候補を最大4件選ぶ。親でdriverを
loadせず、共通45秒budget内で子process検査を順に行い、失敗した候補の後も試す。
読めないmetadataや全候補失敗は既存のOpenGL fallbackへ進む。手動vendor指定も残す。
環境変数の空指定・優先順位・順序・重複・不正JSON・非Mesa・候補数制限の検査を追加した。

Gradle build／baselineコンパイル、実Zinkのindexed状態・drawable resize・compute・
外部画像同期・hidden WSI probe、launcher構文、2本のprofile unittest、配布JARの
新class包含検査が通過した。ローカル証跡は`blit-optimization-build.log`と
`blit-optimization-probe.log`。FPS測定は実施していない。

改善後の配布JAR（SHA-256
`7ac108c8d5360cfab4a555f57a2cf9fdd698ac5e6583e98cc3393192dbc717bb`）を、
専用X上のIris／Sodium最小構成と指定Complementaryで1回起動した。
異なるmetadata directoryを明示し、先頭の壊れたMesa候補を子processが拒否した後、
2番目の有効な候補でAUTO→Vulkanが選ばれることも同じ実行で確認した。
world／resize／shader off→on／shader-only reload／resource reload／disconnectが
通過し、実Vulkan共有画像で最終出力markerを2022回照合して正常終了した。
証跡はローカル`minimal-blit-optimization-launch.log`とprivate instanceの
`afv-production-client.log`、`.allforvulkan/preflight/zink-probe-{1,2}.log`。
全GPU・全MODへ検証範囲を広げることを改善開始の条件にはせず、追加のDTFuel一式や
ディメンション試験は今回は反復していない。以前のDTFuel通過結果は上記の版に対する
証跡として扱い、今回のJARで全packを再確認したという意味にはしない。

## Vulkan転送コマンドの再利用（2026-10-24）

GL→共有画像→WSIのコピーと同期を保持したまま、毎frameのVulkan command bufferの
reset／begin／barrier・blit記録／endを廃止した。共有frame slotとswapchain imageの
組合せごとに、swapchain再作成時に1度だけ記録する。slot fenceの完了を待ってから
再submitし、同じcommandをpendingの間に再利用しない。swapchain imageの取得と
imageごとのpresent semaphoreの方針も維持する。

[Vulkanのcommand buffer lifecycle](https://docs.vulkan.org/spec/latest/chapters/cmdbuffers.html)
に従い、再利用するcommandにはONE_TIME_SUBMIT／SIMULTANEOUS_USEを指定しない。
resize／VSync変更時はdevice idle後、画像・readback bufferを破棄する前に全cacheを
freeし、次のgenerationを記録する。初回の共有画像ownership設定には別commandを使う。

明示的な最終出力診断ではreadback付きvariantも事前記録する。通常variantと交互に
実行し、診断のために通常の再利用経路を迂回しない。smokeは記録数と120回以上の
実再利用を要求し、`AFV_ZINK_TRANSFER_REUSE_PASS`を出す。FPS測定は行わない。

Gradle build／baselineコンパイル、配布JAR検査（16 nested libraries）、launcher構文と
差分の空白検査が通過した。同じ専用X、Iris 1.8.14-beta.1／Sodium 0.8.13最小構成、
指定ComplementaryでAUTO→Vulkanの実クライアントを1回起動した。world、resize、
shader off→on／shader-only reload、resource reload、実iconify中の停止と復帰、
VSync変更と復帰、disconnect、タイトル復帰と終了コード0を確認した。

6 generationで計72 commandを記録し、2582回の実Vulkan表示のうち2544回は既存の
commandを再利用した。通常variantと診断variantの両方を使い、1288回の最終出力を
実Vulkan画像で照合した。shader ZIPのSHA-256と元の9設定もprivate copyと一致した。
この変更後にDTFuel全204 MODの再試験やFPS比較は行っていない。Vulkan validation
layerはこの環境に未導入で、VUID／同期validationを通過したという主張はしない。

配布JARのSHA-256は
`734af2d364b0b4b502afc9a6e2e1dd522e92e6f2bae4811af4876ed4b0196261`。
ローカル証跡は`transfer-reuse-build.log`、`minimal-transfer-reuse-{launch,client,preflight}.log`、
`minimal-transfer-reuse-result.json`。元pack・既存save・提供shaderは変更していない。

## surface対応を推測しないqueue選択（2026-10-24）

従来のnative rendererには、present familyが見つからないとcompute familyで代用する
分岐が残っていた。また、未対応GPUのqueue調査で例外を投げるため、複数GPU列挙の
先頭が未対応だと、後続の対応GPUを調べる前に中断する可能性があった。

`QueueSelection`へ選択処理を集約し、ゲーム本体とZinkのhidden WSI preflightが同じ
実surface照会を使うようにした。combined graphics/presentを優先し、別present familyも
許可する。present不可は推測で補わず、queue count 0は候補から除く。nativeで不足した
graphics／present／computeはunsuitableとして返し、他GPUの検査を継続できるようにした。
選択済みGPUでも論理device作成直前に適合性を確認する。AFVのGL client用VkDeviceには
従来通りcompute／専用transferを要求せず、Zinkと同じGPU UUIDの制約を維持する。

専用transferがなければ、[Vulkan仕様の暗黙の転送対応](https://docs.vulkan.org/refpages/latest/refpages/source/VkQueueFlagBits.html)
を持つgraphics／computeを使用する。TRANSFER_BITを別途報告しないGPUも、不必要に
拒否しない。従来の専用compute／transferの優先は保持する。

CPU検査ではshared／split present、後続のcombined family、count 0、暗黙のtransfer、
専用queue、present不可、compute／graphics不足、未対応GPUに続く対応候補を確認した。
これらはcapability policyの検査で、実際に複数GPUを載せたPCの検証ではない。
Gradle build／baselineコンパイル、配布JAR検査と差分空白検査も通過した。
実RTX 3060では新しい共通queryを使うhidden WSI、GL状態、default drawable、compute、
外部画像・双方向semaphoreのprobeがすべて通過した。

さらにAFV単体のnative Vulkan rendererで新規worldを開き、1282 visible sectionと
59210回のterrain draw、resize、resource reload、disconnectと正常終了を確認した。
実Irisの描画全体やDTFuel一式は今回再試験していない。前節の描画／転送改善は保持する。
配布JARのSHA-256は
`e1860248e0d595fd17e13212573a00cd0099f0cb5438939768f2e61e675147e0`。
ローカル証跡は`queue-selection-build.log`、`queue-selection-probe.log`、
`native-queue-selection-client.log`。元pack・既存save・提供shaderは変更していない。

## 描画先をbindしないGL転送（2026-10-24）

通常のdefault→共有画像コピーを`glBlitNamedFramebuffer`に変更した。転送先FBOを
draw targetへbindせず、MODのdraw bindingはコピー処理中も変更しない。
`GL_READ_BUFFER`にはnamed queryがないため、read targetが既に0ならそのまま照会し、
別FBOなら照会の間だけ0へ切り替えて即時復元する。default read selectorも、必要な
場合だけnamed APIで変更・復元する。変更前に毎frame5回あったFBO bind呼出しは、
read targetが0なら0回、別FBOなら2回になった。

defaultのfront／back選択はcontext作成時に1度調べ、同じEGL configを使うdrawableの
生存期間中は保持する。default framebufferの完全性確認は作成・resize時に移し、
毎frameの確認をなくした。scopeの状態照会も6回から4回へ削減した。
コピー範囲・色変換・向き・GPU ownership／semaphore・Vulkan command再利用は維持する。

[KhronosのDSA仕様](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/ARB/ARB_direct_state_access.txt)
に従い、framebuffer 0をnamed blit／read selector／completeness queryに使用する。
起動前の実GPU検査は、producer read targetが0／別FBOの両方、default read selectorが
`GL_NONE`／使用可能bufferの両方、通常・例外時の復元、転送中のread／draw binding不変、
正確なRGBAと後続MRTの画素一致を確認した。resize後もキャッシュしたfront／back選択が
driverの実際の値と一致した。Gradle build、baselineコンパイル、配布JAR、GL状態・
drawable・compute・外部画像同期・hidden WSI probeが通過した。

上記のGL状態処理・Vulkan command再利用・queue選択をすべて含む同じ配布JARで、
DTFuelの独立コピー（元204 MODを起動前照合、NeoForge 21.1.251、Iris 1.8.14-beta.1、
Sodium 0.8.13）と指定Complementaryを1回起動した。AUTO→実Vulkan表示、実Iris
pipeline有効、新規world、resize、resource reload、disconnect、タイトル復帰と
client／launcherの終了コード0を確認した。4278回のVulkan表示、4266回のcommand
再利用（記録は2 generation計24個）、2138回の最終出力markerのVulkan画像照合が通過した。
配布JARに埋めた起動前helperからも新しいnamed blit検査の成功markerを確認した。
元shader ZIPのSHA-256と9つの設定値はprivate copyと一致した。FPS測定やdimension／
shader切替cycleの反復は行っていない。

ローカル証跡は`named-blit-{build,probe}.log`、`dtfuel-named-blit-{launch,client,preflight}.log`、
`dtfuel-named-blit-result.json`。元pack・既存save・提供shaderは変更していない。
配布JARのSHA-256は
`85dbb429ae925eeb586539a27fcc867a004d4fe6c05342a01797c92cf21d81cb`。

## 表示専用共有画像の転送レイアウト（2026-10-25）

表示用の色画像だけを`SharedImage.forGlCopy`で作成し、Vulkan release／GL waitには
TRANSFER_DST、GL signal／Vulkan acquireにはTRANSFER_SRCを使うようにした。
Vulkan acquireのGENERAL→TRANSFER_SRCへのレイアウト変更をなくし、所有権移譲の
barrierと双方向semaphoreは保持する。sampling／rendering／depth用の汎用画像は
従来のGENERAL契約を使う。表示用画像を誤ってsamplingやVulkan scene入力に使う
呼出しは拒否する。画面コピーの内容・向きやIrisの描画順は変更しない。

[EXT_external_objectsのsemaphore仕様](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/EXT/EXT_external_objects.txt)
のレイアウト対応に従い、GL driverが必要な内部遷移を行う。SignalSemaphoreEXTは
signal直後のflushも行うため、`SharedImage.endGlWrite`の重複した`glFlush`を削除した。
これはAPI呼出しとレイアウト変更の削減であり、FPS向上幅を測定した結果ではない。

起動前helperは本番のSharedImageをGENERAL／転送レイアウトの両方で作成し、16×16と
31×31の各サイズで8 frameずつ色を変え、GL→Vulkanの全RGBAを照合する。明示的な
glFlushは挟まず、実external memoryと双方向semaphoreを使用する。コピー専用の成功
markerも起動条件に追加し、失敗時はゲームにGL driverを入れる前にOpenGLへ戻す。
最終出力診断はframe slotの一巡ごとに通常／readbackを切り替え、両slotで両variantを
使用するようにした。

build／baselineコンパイル、配布JAR、差分空白検査が通過。専用Xが終了していたため
GPU検査を保留し、Xephyr :97とmetacityを専用workspaceに立て直した。以後の実GPU検査で
GENERAL／転送レイアウト各2サイズ、indexed state、default drawable、compute、外部画像、
hidden WSIが通過した。ログはローカル`copy-layout-{build,probe}.log`。

同じ配布JARを専用のIris 1.8.14-beta.1／Sodium 0.8.13最小構成、NeoForge 21.1.251、
指定Complementaryで1回起動した。埋め込みhelperでも両契約・両サイズの成功を確認し、
AUTO→Vulkan、各swapchainで`sharedLayout=transfer`を確認した。world、resize、
shader off→on／shader-only reload、resource reload、実iconify中の停止・復帰、
VSync変更・復帰、disconnectと正常終了を通過。2177回の実Vulkan表示、2105回の
command再利用（6 generation計72個を記録）、1086回の最終出力照合が通過した。
指定shader ZIPのSHA-256と9つの設定値もprivate copyと一致した。今回の版ではDTFuel
全204 MODの再起動やFPS比較は行っていない。

配布JARのSHA-256は
`59b971dace89424cd9fafbfc53834f6987c63b829c677dafe8902f256f74a63a`。
詳細はローカル`minimal-copy-layout-result.json`、実行証跡は
`minimal-copy-layout-{launch,client,preflight}.log`。元pack・既存save・提供shaderは
変更していない。

## surfaceの再作成と軽量FPSチェック（2026-10-25）

`VK_ERROR_SURFACE_LOST_KHR`を検出した場合は、GL側とVulkan deviceの処理完了を
待って古いswapchain・共有画像・記録済みcommandを破棄し、同じGLFW windowに対して
実surfaceを再作成する。新surfaceのpresent対応を既存queueで確認し、acquire用
semaphoreも作り直す。GLコンテキスト、Vulkan device、ワールド、Iris pipelineは
保持する。成功した実presentまで最大3回に制限し、継続不能時は次回起動のOpenGL
復旧markerを残す。実行中に別GPUや別描画backendへ切り替える処理ではない。

surface capabilities／formats／present modesの照会はVkResultを確認してから使用する。
列挙中に件数が増えた場合は最大3回再照会し、失敗時の未初期化データを使わない。
window-cycle検証に、正常なwindowの実surfaceを意図的に破棄・再作成する段階を加えた。
OS由来のsurface-lossエラーそのものを再現する試験ではないが、同じ復旧処理を通り、
同一device／GLコンテキストと有効なIrisを保持したまま実Vulkan表示が再開することを
検査する。

各セクションの参考指標として、同条件の短いFPS測定を記録する。今回は専用Xephyr
:97上のIris／Sodium最小構成、NeoForge 21.1.251、指定Complementary Reimagined
r5.9.3と元の9設定値を使用する。固定seedのflat worldと検証scene、カメラ
(0.5, -60.0, 0.5)、yaw 0／pitch 8、960×540、描画／simulation距離12、VSyncなし、
フレーム制限260（Minecraftの無制限設定）。world安定待ち120 ticksに追加600 ticks
を置き、400 ticks（約20秒）だけ計測する。FPS試料はresize／resource reload／
window-cycleより前で取得し、GPU→CPU readbackを伴う最終出力診断は無効にする。
RenderFrameEvent.Post間隔から算出するため、pacingと表示待ちを含み、GPU単体時間
ではない。実DTFuel環境や普段の約80 FPSとの直接比較には使わない。

launcherは計測前に古いJSONを削除し、今回の計測完了markerとJSONを要求する。
配布JAR・shader・MOD一覧・起動条件を添えたJSONをprivate clientの
`.allforvulkan/benchmarks/`に時刻別で保存する。

ビルド／baselineコンパイル、配布JAR検査、launcherのPython構文検査、MOD profileの
2テスト、差分空白検査が通過。上記最小構成でAUTO→Vulkan、world、resize、resource
reload、実iconify／復帰、VSync変更／復帰、実surface再作成、disconnectと正常終了が
通過した。surface再作成後に有効なIris pipeline、同一GLコンテキストとVkDevice、
非黒色のGL最終出力、実Vulkan presentの再開を検査した。6 generationで36個の転送
commandを記録し、4995回再利用した。今回はCPU readbackによる最終画像の画素照合、
OS由来のsurface-loss注入、DTFuel全204 MODの再起動は行っていない。

| 約20秒の単発測定 | 平均FPS | median frame ms | p95 frame ms | p99 frame ms |
| --- | ---: | ---: | ---: | ---: |
| 変更前 `5037251` | 51.61 | 19.50 | 22.73 | 25.39 |
| surface復旧追加後 | 51.35 | 19.55 | 22.83 | 25.61 |

測定時間は19.978秒／19.980秒、frame数は1031／1026。JSONのrenderer、待機ticks、
サイズ、距離、VSync、frame制限、カメラ、display、focus、計測方式の一致を確認した。
平均FPS差は約-0.5%で、単発の参考値から性能改善や有意な低下を断定しない。
shader ZIPのSHA-256と9設定値は元ファイルとprivate copyで一致する。

変更後の配布JARのSHA-256は
`a88e88e00e303e26d9006d68ece85f72a5709ee8f6a360041af94f828b066bc6`。
ローカル証跡は`surface-recovery-{baseline,after}-{benchmark.json,client.log,launch.log}`、
`surface-recovery-after-preflight.log`と`surface-recovery-build.log`。
元pack・既存save・提供shaderは変更していない。

## 転送前後の画像同期のまとめ（2026-10-25）

共有画像のEXTERNAL acquireと表示画像のTRANSFER_DST準備を1回の
TOP_OF_PIPE→TRANSFER依存にまとめ、共有画像のEXTERNAL releaseと表示画像の
PRESENT遷移を1回のTRANSFER→BOTTOM_OF_PIPE依存にまとめた。各画像のaccess mask、
layout、queue family、subresource rangeを保ち、通常転送のimage依存commandを
4回から2回にする。初期所有権移譲と既存native経路の単独barrierはそのまま使う。
Iris pass、外部semaphore、fence、present semaphore、画像内容は変更しない。
これは記録済みcommandのGPU側同期表現の削減であり、毎frameのJava記録呼出しを
削減したという意味ではない。

[Vulkanのpipeline barrier仕様](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdPipelineBarrier.html)
に従い、同じ実行scopeの異なる画像を配列で指定する。診断readback variantも同じ
まとめた依存を使用する。

ビルド／baselineコンパイル、配布JAR検査、差分空白検査が通過。同じIris／Sodium最小
構成・指定Complementary・9設定値、960×540、距離12、固定scene／視点、VSyncなし、
追加600 ticks待機後400 ticks計測、最終出力readbackなしで短いFPS試料を取得した。
前節の変更後試料（`0b9054a`）とJSONのrenderer、world待機ticks、解像度、距離、
VSync、frame制限、カメラ、display、focus、計測方式は一致した。

| 最小構成・約20秒の単発測定 | 平均FPS | median frame ms | p95 frame ms | p99 frame ms |
| --- | ---: | ---: | ---: | ---: |
| 変更前 `0b9054a` | 51.35 | 19.55 | 22.83 | 25.61 |
| 同期commandをまとめた版 | 53.64 | 18.10 | 21.91 | 24.74 |

今回は1071 frames／19.966秒。専用Xを同じ1280×720設定で再起動しており、時間帯も
異なる単発試料なので、差をこの変更だけの改善率とは扱わない。実DTFuelの普段の
FPSとの直接比較にも使わない。最小構成ではworld、resize、resource reload、実iconify、
VSync切替、実surface再作成、同一context／device／Iris維持、disconnectと正常終了も
通過し、5093回の転送command再利用（6 generation計36個）を確認した。

配布JAR SHA-256は
`36add41ceb369beaf7495fe9e00bfb01b87d0699f222b4b0b7eee8cb82d8bece`。
証跡はローカル`batched-barriers-build.log`、
`minimal-batched-barriers-{benchmark.json,launch.log,client.log,preflight.log}`。

続いて同じJARをDTFuel 0.1.10の全204 MOD・NeoForge 21.1.251の専用コピーで検証した。
指定Complementaryを有効にしてAUTO→Vulkan、実world／resize、shader off→on／
shader-only reload（それぞれ異なる実pipeline）、resource reload、実iconify／復帰、
VSync変更／復帰、実surface再作成と同一GL context／VkDevice／Iris維持、disconnect、
正常終了を通過。Vulkan共有画像の最終出力markerを7383回照合し、まとめた依存を使う
通常／readback両variantで転送commandを14684回再利用した（記録84個）。shader ZIPの
SHA-256と元の9設定値の一致も確認した。この実行はreadback診断を有効にしているため
FPS試料には使用していない。

起動後はDTFuelの更新確認画面がTitleScreenへの移行を待たせていた。実プロセスの
thread dump、専用Xのwindow ID／PID／boundsと単一clientのstackingを確認し、
Minecraft client領域960×540だけを取得して原因を確認した。同じ専用clientで
「Not now」を押して継続し、pack更新は行っていない。この介入を記録し、自動試験が
最初から無人で通ったとは扱わない。見えていた停止はJava deadlockやGL→Vulkan転送
停止ではなかった。ローカル証跡は`dtfuel-batched-barriers-{launch.log,client.log,
preflight.log,manifest.json,result.json,thread-dump.txt}`、更新画面は
`dtfuel-update-prompt.png`と`dtfuel-update-prompt-target.json`。
元pack・既存save・提供shaderは変更していない。

## 同一形式の画像コピーと上下反転の統合（2026-10-25）

UNORMを優先する既存方針を保ち、同じencoding内ではRGBA共有画像とsurfaceの形式が
一致する組を優先する。一致した場合は既存GL blitに上下反転をまとめ、Vulkan側は
同じ形式・同じ大きさの`vkCmdCopyImage`にする。BGRA/RGBA等の形式が異なる組は
従来のVulkan blitによる変換と反転を保つ。共有memory・semaphore・所有権移譲・
fence・command再利用は維持し、Irisの描画passやシェーダー設定は変更しない。

GLとVulkanの転送処理を共通の`PresentationTransfer`にまとめ、起動前GPU検査でも
本番と同じ実装を使う。copy／blit各経路で16×16と31×31、各8 frameの非一様RGBA画像
を転送し、全画素の位置・色・上下方向を照合する。WSIがBGRAだけを提供する環境でも、
同じGPU上の専用RGBA targetでcopyを実行する。両経路の成功markerを起動条件に加えた。
最終出力marker診断も、GL側で反転した場合の座標を読むよう更新した。

ビルド／baselineコンパイル、形式選択の回帰検査、配布JAR検査、差分空白検査が通過。
隔離GPU helperでcopy／blitの2経路・2サイズ・各8 frameの非一様画像の全画素照合が
通過した。NVIDIA RTX 3060／専用X :97の実WSIはBGRA UNORM（format 44）で、
実ゲームは従来のblitを選択した。同一形式copyを実WSIに表示する動作は、このsurface
では未検証であり、専用RGBA targetでの実GPU転送検査と区別する。

Iris／Sodium最小構成、指定Complementaryと元の9設定値、960×540、距離12、固定scene／
視点、VSyncなし、追加600 ticks待機後400 ticks計測、readback診断なしで参考値を取得。
前節とJSONのrenderer・待機ticks・解像度・距離・VSync・frame制限・カメラ・display・
focus・計測方式が一致した。

| 最小構成・同じblit経路の約20秒単発測定 | 平均FPS | median frame ms | p95 frame ms | p99 frame ms |
| --- | ---: | ---: | ---: | ---: |
| 変更前 `f6b7276` | 53.64 | 18.10 | 21.91 | 24.74 |
| copy対応を含む版 | 54.61 | 18.00 | 20.90 | 23.46 |

今回は1091 frames／19.978秒。両方とも従来blit経路なのでcopy経路の改善値とはせず、
単発の回帰指標として扱う。world、resize、resource reload、実iconify／復帰、VSync、
実surface再作成、同一context／device／Iris維持、disconnectと正常終了が通過した。
転送commandは5033回再利用、36個を記録した。

配布JAR SHA-256は
`f6456881ca338251ad35fb4f122a2ebd138a798360f28836cf068ae88cb96de5`。
証跡はローカル`direct-copy-build.log`、
`minimal-direct-copy-{benchmark.json,launch.log,client.log,preflight.log,manifest.json}`。

同じJARでDTFuel 0.1.10・全204 MOD・NeoForge 21.1.251・指定Complementaryを再検証した。
AUTO→Vulkan（実surfaceはblit）、world、resize、shader off→on／shader-only reload、
resource reload、実iconify／復帰、VSync切替／復帰、実surface再作成、同一GL context／
VkDevice／有効Irisの維持、disconnectと正常終了が通過した。GPU共有画像の最終出力
markerを3521回照合し、転送commandの6962回再利用（記録84個）を確認した。
埋め込み起動前helperでもcopy／blit・2サイズ・各8 frameの全画素照合が通過した。
shader ZIPのSHA-256と9設定値は元ファイルとprivate copyで一致した。

今回もDTFuel更新確認がTitleScreenへの移行を待たせた。専用Minecraftの現在のPID、
window ID、bounds、単一clientのstackingを確認し、960×540のclient領域だけを取得した。
画面を確認して同じ実行中の専用clientの「Not now」を押し、更新せず検証を継続した。
無人で全手順が通ったという扱いにはしない。readback診断を伴うこの全MOD実行の
FPSは測定していない。元pack・既存save・提供shaderは変更していない。

全MODの証跡はローカル
`dtfuel-direct-copy-{launch.log,client.log,preflight.log,manifest.json,result.json}`、
更新画面と対象metadataは`dtfuel-direct-copy-update-prompt{.png,-target.json}`。
同一形式copyによる実WSI表示、sRGB surfaceへのcopy、別GPU／別OSの動作と性能は
今回のハードウェアでは未検証であり、全Iris環境の完全互換を示す結果ではない。

## 別版のIris／Sodiumを使うAFV本体の検証（2026-10-25）

これまでAFVなしの比較用だった固定済みIris 1.8.12／Sodium 0.6.13のJARを、
`compatibility/iris-baseline.lock.json`のSHA-512と照合して独立コピーする準備経路を
追加した。sourceの比較環境を編集せず、lockで選んだJARだけをコピーし、config、
overrides、saveはコピーしない。ロックと明示したMOD名の併用、ロックなしのruntime
上書きを拒否し、チェックサム不一致では専用clientを作成しない。lock本体のSHA-256、
版情報とMODの固定値をsource manifest／起動manifest／benchmark metadataへ記録する。

元lockのNeoForgeは21.1.228で、今回の実行runtimeは明示指定したインストール済み
21.1.251。両方を記録し、21.1.228でproduction起動したという扱いにはしない。
Pythonのprofileテスト3件、構文検査、差分空白検査が通過した。renderer Javaは変更
しておらず、配布JARは前節のSHA-256 `f6456881ca338251ad35fb4f122a2ebd138a798360f28836cf068ae88cb96de5`
を使用する。

この組合せのproduction JAR起動はAUTO→Vulkanを選択し、指定Complementary Reimagined
r5.9.3でworld、resize、shader off→on／shader-only reload（別の実pipelineを確認）、
resource reload、実iconify／復帰、VSync切替／復帰、実surface再作成、同一GL context／
VkDevice／有効Irisの維持、disconnectと正常終了を通過した。転送commandを5287回
再利用し、42個を記録した。

最小構成の同じ固定scene／視点、960×540、描画／simulation距離12、VSyncなし、
追加600 ticks待機後400 ticks計測、最終出力readbackなしの約20秒試料は、平均52.98 FPS、
median 18.31 ms、p95 22.77 ms、p99 24.00 ms（1059 frames／19.988秒）。これは別版の
依存MOD構成の参考値で、前節からのAFVの改善／悪化率には使わない。
起動・計測metadataにlockの版と実runtimeの両方が記録されたことも確認した。
証跡はローカル`locked-iris-profile-checks.log`、
`iris-1.8.12-fps-{launch.log,client.log,preflight.log,manifest.json,benchmark.json}`。

同じ別版profileで、readback診断付きの2回目のproduction起動も実施した。地上→ネザー→
エンド→ネザー→地上の実dimension移動、各stageで実client chunks・Irisのdimension／
独立pipeline・同一GL context・120 ticksと実表示120回以上を確認し、元のworldへ戻って
正常終了した。3種類のdimension pipelineを確認し、GPU共有画像の最終出力markerを
1839回照合、転送commandを3657回再利用（記録24個）した。こちらはFPS測定とは別の
診断実行で、どちらの起動も手動UI介入なしで通過した。

source cacheとprivate copyの両JARがlockのSHA-512に一致し、元の提供shaderとprivate
copyのZIPおよび9設定値も一致した。Iris 1.8.12／Sodium 0.6.13に対して今回のシナリオで
描画本体の修正が必要になる問題は見つからなかった。AFVなしの比較環境は変更していない。
証跡はローカル`iris-1.8.12-dimensions-{launch.log,client.log,preflight.log,manifest.json,
source.json,result.json}`。この結果は2 MODのprofileであり、DTFuel全204 MODをこの旧版の
組合せへ置換した検証や、全pack／全OS／全GPUの完全互換を示すものではない。

## 実験: 表示API待ちと次GLフレームの重ね合わせ（2026-10-25）

実験用`afv.experimentalAsyncPresent`を追加し、`vkQueuePresentKHR`を専用workerへ移す。
現在frameの転送submit後、次frameのGL描画を先に進め、その後のAFV queue／swapchain
操作前に前の結果へ合流する。未完了は最大1件で、native structはworker自身のstackに
確保する。Iris passとGL描画、共有画像・semaphore・fenceの契約は変更しない。
通常設定は同期実行のままとし、同一JARでOFF／ONを比較する。

起動・benchmark metadataに実験のON／OFFを記録し、終了時に実行全体の完了call数、
native API時間と合流待ち時間の平均を出す。後者は起動やライフサイクル試験も含む
集計で、FPSの約20秒区間だけの値やGPU単体時間とは区別する。表示済み数は完了した
成功／suboptimal結果だけで増やす。最小化検査では直前の未完了callを回収してから
休止中の表示数を測る。

ビルド／baselineコンパイル、dispatcherのthread・非同期重ね合わせ・未完了1件制限・
例外伝播・割込み時の完了回収・終了の検査、配布JAR検査、launcher構文検査、差分空白
検査が通過。同じJARでIris 1.8.14-beta.1／Sodium 0.8.13最小構成と指定Complementary
r5.9.3をOFF→ONの順に各1回起動した。固定scene／視点、960×540、距離12、VSyncなし、
追加600 ticks待機後400 ticks計測、最終出力readbackなし。JSONのrenderer、待機ticks、
解像度、距離、VSync、frame制限、カメラ、display、focus、計測方式と、JAR／MOD／
shader／shader設定のhashが一致した。

| 同一JAR・最小構成の約20秒単発比較 | 平均FPS | median frame ms | p95 frame ms | p99 frame ms |
| --- | ---: | ---: | ---: | ---: |
| 実験OFF（同期） | 46.72 | 21.15 | 23.48 | 24.70 |
| 実験ON（非同期） | 55.98 | 17.94 | 20.27 | 21.53 |

今回のFPS差は+19.82%、p95時間差は-13.65%。
OFFは934 frames／19.992秒、ONは1118 frames／19.971秒。
反復統計や実DTFuel全構成の改善率ではなく、同じ専用X上の単発A/Bの参考値とする。
両方ともworld、resize、shader off→on／shader-only reload、resource reload、実iconify／
復帰、VSync切替／復帰、実surface再作成、同一context／device／Iris維持、disconnectと
正常終了を通過した。

実行全体では同期4843 callsのnative平均16.28 ms、非同期5792 callsのnative平均15.33 ms、
合流待ち平均12.41 msだった。これらはFPS試料区間外やVSync切替の段階も含み、
GPU時間や測定区間の厳密な待ち時間内訳には使わない。
配布JAR SHA-256は`ea6c77499f5c447fd0b9def50a850d1889d62b5708dba09062e6e92c7cc2dca0`。
証跡はローカル`async-present-build.log`、`async-present-comparison.json`、
`async-present-{off,on}-{launch.log,client.log,preflight.log,manifest.json,benchmark.json}`。

続いて同じJARをDTFuel 0.1.10全204 MOD・NeoForge 21.1.251・指定Complementaryで
実験ON、最終出力readback診断付きで起動した。AUTO→Vulkan、world、resize、shader
 off→on／shader-only reload、resource reload、実iconify／復帰、VSync切替／復帰、
実surface再作成、同一GL context／VkDevice／有効Irisの維持、disconnectと正常終了が
通過した。GPU共有画像の最終出力markerを3679回照合し、転送commandを7277回再利用
（記録84個）した。実行全体の非同期present完了は7363 calls、native平均15.18 ms、
合流待ち平均3.02 ms。これはreadback診断付きの全段階集計なので、通常DTFuelのFPS
測定や内訳には使わない。全204 MODでの実験OFF／ONのFPS改善率は今回測っていない。
shader ZIPと元の9設定値の一致を確認した。

pack更新画面は専用Minecraftの現在のwindow ID／PID／bounds／stackingを確認して
client領域960×540だけを取得し、表示されていた「Not now」で同じprocessを継続した。
pack更新はしておらず、この手動介入は記録する。元pack・既存save・提供shaderは
変更していない。証跡はローカル`dtfuel-async-present-{launch.log,client.log,preflight.log,
manifest.json,result.json}`、更新画面と対象metadataは
`dtfuel-async-present-update-prompt{.png,-target.json}`。

このセクションは描画進行を変える性能実験の実装・比較であり、標準はOFFのまま。
JVM引数`-Dafv.experimentalAsyncPresent=true`で試せる。描画本体と画像転送は同じ経路を
保ち、効果があった仮説として保持する。全GPU・全packでの性能向上を示すものではない。
