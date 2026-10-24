# All For Vulkan 開発計画

更新: 2026-10-24。状態: NeoForge移植とM1接続を実装中。AFV native描画はワールド・色/深度のGL往復・リサイズ・再読込・切断を検証。加えてZink上の既存Iris/Sodium描画からAFV所有VkDevice・WSIへ最終画像をGPU共有するクライアント経路を実装し、DTFuel＋指定Complementaryで実画面と同じライフサイクルを確認した。この経路ではAFV native地形描画を使用しない。全GL機能・全GPU・全Iris packの互換、native地形への任意Iris pass統合、共通コアへの分離は引き続き開発対象。M0/M1全体の合格宣言ではない。

## 目的と対象

正式名称は **All For Vulkan**、略称は **AFV**、MOD IDは `allforvulkan` とする。VulkanMod由来の描画本体、NeoForge対応、GL互換層を一つの統合MODとして開発する。

Minecraft **1.21.1 / NeoForge / Java 21**で、Irisを含む様々なMODのOpenGL呼び出しをVulkanへ変換し、VulkanModの描画と統合する。
All For Vulkanは、**VulkanMod本家を直接フォークしてNeoForgeへ移植し、GL互換機能を組み込むプロジェクト**とする。ユーザーの方針指定により、他のNeoForge移植版への追加MOD方式から変更した。
将来のFabric版と過去のMinecraft向けForge版を視野に、描画・GL互換・資源同期は共通コアへ集約し、ローダー固有処理とMinecraftの版差を分離する。詳細は[共通コア設計](ARCHITECTURE.md)を参照。初期実装対象は引き続きNeoForge 1.21.1とする。
最終目標は汎用的なOpenGL互換性であり、少数のMOD向けの命令置換や、画面に後処理だけをかける機能を完成形にはしない。

「全てのGL呼び出し」は、以下を区別して互換性表として管理する。

- Desktop OpenGL 1.x〜4.6のcore/compatibility機能。古い固定機能、表示リスト等も最終対象として残す。
- MODが利用するARB/EXT等の拡張と、ベンダー固有拡張。実装・代替・GPU制約を個別に記録する。
- LWJGLのGLxx/GLxxC、拡張クラス、配列/Buffer/ポインタ版、ngl系の入口。
- コンテキスト生成、共有、スレッド移動、関数アドレス取得、JNIからの呼び出し。
- GLの外側にあるMinecraft/Iris/Sodiumの描画フック、頂点形式、描画段階。

無制限の拡張と任意のネイティブコードまで無条件に動くという保証は置かない。対象は縮小せず、未対応項目とハードウェア上の制約を公開する。
OpenGL機能の対応と、MOD全体の動作確認は別の判定にする。

## 採用候補と判断

**VulkanModのNeoForge向け独自フォークを開発する。GL変換エンジンの第一候補はMesa OpenGL + Zinkで、フォーク内部に資源・描画統合を実装する。Zinkの採用は接続実証後に確定する。**
ZinkはMesaのGalliumドライバーであり、Java用の小さな変換ライブラリではない。ネイティブMesaのビルド、配布、コンテキストと関数解決の統合が必要になる。[Mesa Zink](https://docs.mesa3d.org/drivers/zink.html)

| 手法 | 長所 | 主な課題 | 判断 |
| --- | --- | --- | --- |
| Mesa/Zinkを組み込み、VulkanModと外部メモリ・同期を共有 | 広いDesktop GL互換性を既存実装から得られる | OS別配布、既存GL互換層との競合、色/深度等の共有、別デバイス間同期 | 第一候補。最初に接続を実証 |
| VulkanModのGL互換層を全面拡張 | VulkanModの資源・キューと直接統合しやすい | GLSL、状態、固定機能、拡張まで独自GL実装を保守する規模 | 比較候補。Zink接続が成立しない場合に再評価 |
| ANGLEのVulkanバックエンド | 既存の変換・シェーダー技術を利用可能 | 主対象はOpenGL ES。Desktop GLを使うMOD全体への直接互換層にはならない | 汎用基盤の第一候補にはしない |
| 通常のOpenGLドライバーとVulkanModを併用 | 個別MODを既存GLで動かす実験がしやすい | GL→Vulkan化という目的と異なる。資源共有の問題も残る | 比較・切り分け用 |
| Minecraft全体をZinkで動かす | Iris/SodiumとZinkの基準動作を確認できる | VulkanModへの統合を満たさない | テスト用対照構成 |
| Zinkの既存GLクライアント出力をAFV VkDevice・WSIへGPU共有 | 既存Iris/Sodiumの資源管理・複数passを維持し、AFV側が表示と同期を所有できる | AFV native地形描画とは別モード。OS別Mesa配布・GL context形式・他GPUの検証が必要 | Linux実装とDTFuel実機接続を追加。完全互換の判定は継続 |
| Iris互換シェーダーパックをVulkanで再実装 | 描画経路を最適化しやすい | Iris自体や他MODのGL呼び出しへの汎用対応にはならない | Iris対応での補助手段 |

ANGLEの対象は[公式README](https://chromium.googlesource.com/angle/angle/+/main/README.md)を参照。既存の限定的なシェーダーエンジンとの違いは[調査記録](RESEARCH.md)に記載。

## フォークと移植の方針

起点は **xCollateral/VulkanMod本家のMinecraft 1.21.1対応ソース**とする。M0で対応版のソースとcommitを固定し、そこからNeoForgeへ直接移植する。別のMinecraft版の最新ブランチをそのまま使わない。
調査済みの本家`0.5.5`ソースは`f23855f2675f1efb52fb8aa2c26bc23dccfb5543`。公開`0.6.7+1.21.1`とのソース対応も確認し、採用起点を決定する。

移植ではビルドとmapping、MODメタデータ、起動処理、設定・イベント、リソース読込、Fabric固有APIと描画連携、mixin/access widenerを含めてNeoForge向けに置き換える。
この際、GPU描画本体とGL互換処理を共通コアへ切り出し、Minecraft依存の描画入力・mixinは版別アダプター、NeoForge API依存はplatformモジュールへ配置する。本家のFabric固有処理は描画コアへ残さず、将来のFabricアダプターで再利用できるよう整理する。
既存移植版は移植差分の参考として利用し、All For Vulkanの必須依存にはしない。コードを取り込む場合は由来・ライセンス・変更点を記録する。

フォーク内でウィンドウ初期化、Vulkanデバイス生成、メモリアロケーター、描画段階、GL mixinを直接変更できる構成にする。
Mesa/Zinkとの接続層とIris向け処理は内部モジュールに分け、VulkanMod本体への場当たり的なフック追加を避ける。
Java側は対象Minecraft版・ローダー別に一つの描画MODとして配布し、各JARに共通コアを内包する。Mesa/ZinkのネイティブバイナリはOS・CPU・ABI別依存として扱う。未改造VulkanModや他の移植版との同時導入を前提にしない。

Reforged `0618965fbedb71ca2ee2e5d4528fcedc4fb27551`の設定はMC 1.21.1 / NeoForge 21.1.228であり、移植の参考にする。[ソース設定](https://github.com/TrulyRin/VulkanMod-Reforged/blob/0618965fbedb71ca2ee2e5d4528fcedc4fb27551/gradle.properties)
VulkanModNeoForgeの調査時のデフォルト先は1.21.4設定だったため、参考にする場合も対象版を確認する。

## 想定構成

```text
Iris / その他のMOD
  │ OpenGL API・関数ポインタ
  ▼
All For Vulkanフォーク内部: ライブラリ選択 / GLコンテキスト / 呼び出し経路調整
  ▼
Mesa OpenGL state tracker → Zink → Vulkan (Zink所有のデバイス・キューを想定)
  ⇅ 外部メモリと同期、必要な場合はGPU内コピー
All For Vulkanフォーク内部: 資源対応表 / 描画段階の境界 / Irisアダプター
  ⇅
NeoForgeへ移植したVulkanMod描画本体 → Vulkan → 画面表示
```

これは設計案であって、Zinkから任意のVulkanModのVkCommandBufferへ直接命令を渡せると確認したものではない。
最終的なウィンドウ表示とswapchainはVulkanModが所有し、Zinkは原則オフスクリーン描画に使う案を検証する。
独自フォークで初期化や資源生成を直接制御できても、Zinkと描画本体のデバイス・資源・同期の整合性は引き続き必要になる。

### GLの入口と状態の所有

- 本物のMesa GLコンテキストと関数ポインタでLWJGLの機能照会を成立させる。機能フラグを偽装して通さない。
- EGL等によるオフスクリーンコンテキストを候補とし、GLFWのVulkanウィンドウと切り離した初期化を試す。
- VulkanMod既存のGL mixinとMesaが同じ呼び出しを二重処理しないようにする。混在するテクスチャID・FBO IDの意味を明示する。
- 生のGL資源はMesa、VulkanMod内部資源はVulkanModが所有し、共有対象だけ対応表を持つ案を優先する。
- Minecraft側のGL状態キャッシュとMODの直接呼び出しの整合性、FBO 0とゲームの描画先の対応も実装対象。
- JNIがOSの別GLライブラリを直接開く場合、LWJGLの差し替えだけでは捕捉できない。ネイティブの関数解決を含めて経路を記録する。

### 画像・バッファ・同期

- 両経路が同じ物理GPUを使用していることを確認する。同じGPUでもVkDeviceが異なればVkImageハンドルをそのまま共有できない。
- VulkanMod側で共有用画像/バッファを、必要な外部メモリ属性を付けて生成する。既存の通常割当が後からそのままexportできるとは仮定しない。
- GL側の外部メモリimportと外部semaphoreを候補に、実際のZink・GPU・OSが対応するhandle種別、形式、tiling、usage、専用割当条件を照会する。
- 読み書きの境界ごとにsemaphore、画像レイアウト、queue ownership、メモリ可視性を扱う。GPU間の同期をCPU側の順序だけで代替しない。
- 色、深度/stencil、MSAA、mipmap、配列/3Dテクスチャ、UBO/SSBO、readbackを順次対象にする。共有できない形式はGPU内コピー等を評価する。
- viewport、Y方向、深度範囲、sRGB、alpha、blendを揃える。単純な色画像の合成だけでIris対応完了とはしない。
- resize、リソース再読込、ワールド切替、複数フレーム進行中の破棄を扱う。毎フレームのCPU readbackや`deviceWaitIdle`は製品経路にしない。

外部メモリと同期は別々の仕組みであり、両方を設計する。[Khronos資料](https://docs.vulkan.org/guide/latest/extensions/external.html)

### Iris統合

ZinkがGL命令を実行できても、IrisのMinecraft/Sodiumへの組み込みは自動解決されない。
Irisの実際のJARの依存関係、mixin、地形頂点形式を固定し、VulkanMod側に必要なフックを用意する。
Sodiumの通常描画とVulkanModの地形描画を無条件に同時実行しない。

元のIrisをアダプターで動かす経路を最初に評価し、それで成立しなければIrisのVulkanMod向けバックエンド/フォークを実装する。
後者は「未改造Irisがそのまま互換」と区別して記載する。依存関係の無効化だけで対応済みにはしない。
shadow、gbuffers、deferred/composite/final、各種uniform、履歴画像、computeを含む描画段階が対象になる。

## 開発段階と合格条件

| 段階 | 実装すること | 合格条件 |
| --- | --- | --- |
| M0: 独自フォークと移植 | 本家1.21.1対応ソース固定、共通コア/Minecraft版/platformの分離、NeoForgeビルド・API移植、単体起動、Iris基準構成 | 独自フォークでタイトルとワールドを表示。コアの依存境界を明示でき、実行環境と比較ログが揃う |
| M1: Zink接続の実証 | オフスクリーンMesa/Zink、実際のGL機能照会、共有色画像と双方向同期、VulkanModで表示 | GLで描いた画像をCPU readbackなしでVulkanModのフレーム内に表示。resize・繰返し描画で破損なし |
| M2: 汎用GL統合 | mixin競合解消、共有資源対応、既定FBO、深度、buffer、状態復元、スレッド/寿命管理 | サンプルMODの途中描画・深度テスト・描画結果の再サンプリングが正しい |
| M3: Iris統合 | Sodium依存と描画フックの解決、地形・entity・shadow・複数pass | 既存パックで地形/影/半透明/後処理を確認。未改造Irisか専用フォークかを明記 |
| M4: 広いGL互換性 | 固定機能/compatibility、compute、SSBO/image、間接描画、sync、拡張、native経路 | API/profile/拡張の対応表と回帰テスト。具体的なMODの未対応を継続的に解消 |
| M5: 配布と性能 | OS別ネイティブ配布、キャッシュ、診断、再起動/再読込耐性、複数GPUメーカー評価 | クリーン環境で導入・起動でき、対応範囲と性能測定を公開できる |

将来のローダー展開では、まず同じMinecraft版のFabricアダプターで共通化を実証し、その後は選定した過去版のMinecraft/ForgeアダプターとJava・LWJGL対応ビルドを追加する。具体的な対象版と実装時期は未確定。Fabric用MODをNeoForge上でロードする機能は、このマルチローダー対応とは別に扱う。

M1は方式選定の判定点。共有色画像だけでなく、M2/M3に必要な深度形式・同期・描画への割込みが可能かをここで照会する。
成立しない場合は、Mesa/Zink側の最小パッチ、共有境界でのGPUコピー、より深いVulkanMod統合の順で評価する。
それでも成立しなければ、独自GL互換層を構築する費用と到達範囲を改めて提示する。限定実装を汎用完成版として扱わない。

段階は実装と検証の順序であり、M1/M2で最終目標を切り下げるものではない。
全工程の日数はM1以前には確約しない。これは通常の描画追加MODより大きく、ネイティブ描画基盤を伴う開発になる。

## 検証方針

- API: オブジェクト生成/削除、照会値、GLエラー、FBO completeness、資源共有、スレッド、同期。
- 描画: 色/深度、透明、sRGB、mipmap、MSAA、複数render target、履歴、compute。小さな決定的シーンで比較する。
- 比較構成: 通常GLのIris/Sodium、ZinkのIris/Sodium、本家VulkanMod、NeoForgeへ移植したAll For VulkanフォークのZink無効/有効、さらにIrisを統合した構成。
- 実運用: resize、リソース再読込、ワールド切替、shader切替、最小化/復帰、長時間動作。
- 性能: 同条件のCPU/GPU時間、frame time分布、VRAM、同期待ち、起動時コンパイルを測る。Vulkan化による高速化は前提にしない。
- GL適合性には利用可能なKhronos/Mesaのテスト群を評価する。テストを通しても未実施の公式認証を名乗らない。
- スクリーンショットが必要な場合は、識別済みのゲームの描画領域だけを取得する。

## 未確定事項と次の実装単位

OS・GPUは未回答。最初の実証は開発環境に合わせたLinux x86_64を仮候補とし、Windows用handle/配布も設計から考慮する。対象OSの回答があれば優先順を変える。
macOSはVulkan実装とGL機能の制約を別に評価する。

次の実装は **AFVフレームへのZink共有画像合成を基に、GL資源の登録・描画pass・IrisのSodium依存経路を統合すること**。フォーク作成とNeoForge移植の初期実装は実施済み。
先に大量のGLラッパーを書くのではなく、最も不確実な資源・同期の接続を実際に成立させる。
その結果で共有境界を固定し、汎用互換層とIris統合へ進む。


## 実装の進捗（2026-10-24、継続中）

- AFVワールドの地形VkDrawIndexed命令を数え、リサイズ・再読込・切断を自動検証。
- `native-bridge`でMesa/ZinkオフスクリーンGL 4.6、computeとimage storeの画素を検証。
- 同一GPUをUUIDで照合し、専用割当のRGBA8画像をopaque FDでGLへimport。
  画像layout・EXTERNAL queue ownershipと双方向の外部semaphoreを使い、
  2サイズ×8往復のGPU共有と数値結果を確認。検証用readbackは製品転送経路ではない。
- Iris 1.8.12 / Sodium 0.6.13と自作テスト用packを固定。
  通常GL・Zinkの対照構成で実IrisRenderingPipelineを確認し、world/resize/reload/disconnectが通過。
- AFV VkDeviceでの共有画像生成、実Zinkコンテキスト、双方向外部semaphore、
  フレームフェンスに沿ったring再利用とswapchainへの32×32診断合成を実装。
  resize時には既存のdevice idle後にringとGL/Vulkan共有資源を再生成する。
- CPUから読み込むMinecraftテクスチャを実GL名で登録し、NativeImage・アトラス・
  フォント・各mip・sampler設定をZinkにも転送する経路を追加。
  リサイズではGL contextとテクスチャを保持し、共有フレームringのみ再生成する。
  GLSLで2×2の入力画像を実サンプリングした32×32領域をVulkan側で照合。
- GL unpack alignment/row/skip、BGRA、packed RGBA/BGRA、PBOオフセットの転送を修正・検証。
- フレーム全体の色画像をGPU上でVulkan→Zink→Vulkanへ往復。
  native-bridgeの `GlFramePass` へ実GL texture/FBOと寸法を渡し、GLSL後処理を実行。
  入力/出力の外部semaphoreと既存フレームフェンスで2段のVk submissionを接続。
  identityとinvertを実装し、小領域の入出力比較をsmoke testへ追加。
- 最優先の残作業は任意の描画先・深度・GPU更新資源の共有と双方向のGL資源経路、
  IrisのSodium依存描画／頂点形式／pass統合。
  起動制限だけを解除してIris対応と扱うことはしない。
- 影・複数pass・各種pack・深度/MSAA等の完全互換テスト、他MOD/OS/GPUの範囲拡張は継続する。


## 追加の完了条件（ユーザー指定、2026-10-24）

- ハードウェアのVulkan対応/API/拡張に応じた安全な無効化・互換動作を実装・検証する。
  未対応時の通常GL復帰を、IrisがVulkan上で動作した実績と混同しない。
- Iris/Sodium等について、同じワールド・描画距離・解像度・shader pack・vsync条件で
  FPSとframe timeを実測し、改善／悪化／未対応を区別して報告する。
- `/mnt/hdd/MinecraftModpacks/DTFuel` を元に専用クライアントを用意し、実運用MOD構成で
  起動・ワールド・描画・再読込等の正常を検証する。元の設定・セーブは保持する。
- この追加条件と元のIris完全互換が確認できるまでゴールを完了扱いにしない。

- 設定画面から自動/Vulkan/OpenGLを選択でき、起動時の判定理由を確認できるようにする。
  backend切替に再起動が必要な場合は明示する。自動復帰をVulkan互換の達成と混同しない。


### 2026-10-24: ワールド深度・通常描画・起動方式

- ワールド色／深度／GL出力を別の外部共有画像にし、NeoForge AFTER_LEVELでGL passを実行。
  手元とGUIはLOADで継続描画。D24S8の実depthサンプリングと小領域の数値照合が通過。
- NeoForgeでJOMLのMixinが適用されない問題を、Vulkanへ渡す投影行列の変換へ置換。
  通常VulkanとZink連携の両方で、HUD・照準・通知・手元をクライアント範囲で視認。
- MOD設定へ自動／Vulkan／OpenGLを追加。Vulkan事前照会、Mixin全停止によるOpenGL復帰、
  起動失敗の次回復旧、間接描画の実ハードウェア機能制限を実装。
- Iris/Sodium/Embeddiumの同時導入は当面OpenGLを選択する。IrisのVulkan描画統合は未達。
- 専用Xephyrセッション:97で検証。通常GLがllvmpipeとなるため、RTX 3060のVulkanと
  数値を比べて高速化と主張しない。FPS/p95/p99計測には実rendererと条件を保存する。
- DTFuel 0.1.10 / NeoForge 21.1.251の204 MODを独立コピーし、全JARのSHA-256を照合。
  元の設定・MOD・ワールドは変更しない。実クライアントの検証は継続中。


### 追加検証対象: Complementary Reimagined r5.9.3

ユーザー指定の `/mnt/hdd/CurseForge/Instances/DTFuel/shaderpacks/ComplementaryReimagined_r5.9.3.zip`
と隣接する `.zip.txt` を専用コピーへ導入。元ファイルを変更せず、設定の9項目を保持して
Irisの実pipeline、ワールド表示、実寸法の変更、再読込・切断を検証する。
AFV本体のVulkan rendererとの統合と、GL→Zink比較構成の成功は区別する。
