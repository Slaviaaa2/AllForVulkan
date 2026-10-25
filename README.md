# All For Vulkan

**All For Vulkan (AFV)** は、VulkanMod本家のフォークを基に開発する、Minecraft **1.21.1 / NeoForge** 向けの統合描画MODです。

VulkanMod由来のVulkan描画本体に、OpenGL互換機能とMOD連携を組み込み、Irisを含む様々なMODの描画をVulkan上で動かすことを目指します。GL変換エンジンにはMesa/Zinkを使用するLinux向け経路を実装しています。

| 項目 | 方針 |
| --- | --- |
| 正式名称 | All For Vulkan |
| 略称 | AFV |
| MOD ID | `allforvulkan` |
| Minecraft | 1.21.1 |
| MODローダー | NeoForge |
| 将来の展開 | 共通コアを使うFabric版・過去のMinecraft向けForge版 |
| Java | 21 |
| 開発形態 | VulkanMod本家を直接フォークし、NeoForge移植・GL互換機能・MOD連携を統合 |

VulkanModタグ `0.5.5`（`f23855f2675f1efb52fb8aa2c26bc23dccfb5543`）を起点に、NeoForgeへ移植しています。導入手順と描画経路の詳細は[開発ガイド](docs/DEVELOPMENT.md)を参照してください。

MOD一覧のAFV設定から **「自動」「Vulkan」「OpenGL」** を選択できます。変更は再起動後に適用します。起動前にVulkan 1.2・必要な機能／拡張・論理デバイス作成を調べ、使えない場合はAFVの描画Mixinを適用せずOpenGLで起動します。起動途中の異常終了時も次回はOpenGLで復旧します。他のMODがある場合、「自動」「Vulkan」は下記のLinux向けZinkクライアント経路を検査し、未対応時はOpenGLを選びます。既知のIris/Sodiumだけでなく、未知のMODでも既存のGL描画処理を保持するための方針です。

**AFV共有表示経路**は、Minecraft・Iris・Sodiumの実OpenGL呼び出しをMesa/Zinkで実行し、最終default framebufferを外部メモリと双方向GPUセマフォでAFV所有のVulkanデバイスへ渡して表示します。画面全体のCPU転送は行いません。GL資源・MRT・影・シェーダーpassは既存のIris/Sodiumと実GLドライバーが管理します。この経路ではAFV独自の地形レンダラーを使用しません。Linuxとインストール済みMesa EGL/Zinkが必要です。別プロセスで実GL 4.6・compute・画像共有と、同じGPUの実surface／swapchain作成を検査し、検査の失敗・異常終了・タイムアウト時はOpenGLを選びます。ドライバー内部のクラッシュや全環境での無事故を保証するものではありません。

Iris/Sodiumとの互換性は開発中です。診断機能と再現手順は[検証ガイド](docs/VALIDATION.md)を参照してください。

**Zink直接表示（実験）** は、設定の「表示経路」または `-Dafv.presentation=NATIVE` で選択できます。Zink自身がウィンドウへ表示し、AFV側の別Vulkanデバイス・共有画像・追加の画面転送を省きます。Iris等のGL描画は維持します。既定は従来のAFV共有表示です。速度差は環境ごとの比較測定が必要です。[詳細と測定方法](docs/DEVELOPMENT.md#experiment-native-zink-window-presentation)。

Zinkクライアントの共有表示では、`vkQueuePresentKHR` を専用スレッドで前フレームの表示と次のGLフレームに重ねます。標準はONです。同期に戻すには `-Dafv.asyncPresent=false`、検証launcherでは `--sync-present` を使います。`-Dafv.experimentalAsyncPresent=true` は明示的にONを記録する旧指定です。比較は同一JAR・同一描画条件で、実デスクトップの手順 [BENCHMARK.md](docs/BENCHMARK.md) に従ってください。ネストしたXサーバ上の数値は効果の根拠にしません。

さらに `-Dafv.experimentalFramePipeline=true`（検証launcherでは `--frame-pipeline`）で、画像取得・転送・表示をまとめて専用スレッドへ渡し、3枚の共有画像でGL描画を先行させる実験を選べます。この設定は非同期表示も含みます。未完了jobは既定で1件、`-Dafv.experimentalPipelineDepth=1|2|3`（`--pipeline-depth`）で比較できます。標準はOFFで、比較測定には描画FPSと実際に完了したVulkan表示数の両方を記録します。

`-Dafv.experimentalDirectOutput=true`（`--direct-output`）は、GLの描画先0を共有画像へ振り向け、最終GLコピーを省く別の実験です。Linux x86_64のZinkクライアント経路用で標準はOFFです。描画後のMODの書き込みも同じ共有画像へ送ります。Vulkan表示先への転送は残ります。実GPUでの入口・状態照会・描画検査を起動時に行います。

直接出力の受け渡しは `-Dafv.directOutputLayout=attachment` が既定です。GL描画用とVulkan転送用のレイアウトを使い分けます。従来の方式との比較には `general`（launcherでは `--direct-output-layout general`）を指定できます。

Linux向け実験機能 `-Dafv.zinkInterop=true` は別の経路で、AFV独自の地形描画からワールドの色と深度をGPU共有画像でZinkへ渡し、GLSL後処理からVulkanへ戻します。処理位置はワールド描画後・手元の深度クリアとGUI描画の前です。`-Dafv.zinkEffect=invert` / `depth` で診断用の色反転／深度表示を選べます。こちらの経路にIrisの地形・影・複数passを統合する機能は開発中です。**Iris全機能・全シェーダーパック・全GPUの完全互換は未確認**です。

設定の「シェーダー経路」または `-Dafv.shaderBackend=direct` は、Zinkを通さないフレームグラフの実験スイッチです。現在の実装はスケジューラと、ゲーム外の合成チェーンまでです。Irisのパスはまだ載っていないため、選んでも現行経路へ戻して理由を記録します。設計は [DIRECT_BACKEND.md](docs/DIRECT_BACKEND.md) を参照してください。

- [ビルド・起動方法と現状](docs/DEVELOPMENT.md)
- [検証ガイド](docs/VALIDATION.md)
- [デスクトップ比較測定](docs/BENCHMARK.md)
- [直接フレームグラフの設計](docs/DIRECT_BACKEND.md)
- [ソースの由来とライセンス](NOTICE.md)
- [開発計画](PLAN.md)
- [共通コア・マルチローダー設計](ARCHITECTURE.md)
- [元プロジェクト: VulkanMod](https://github.com/xCollateral/VulkanMod)

NeoForge移植とMesa/Zinkとの画像・同期共有の試作を進めています。広いOpenGL互換性とIrisの描画統合を段階的に実装し、API・拡張・MODごとの対応範囲を記録します。

描画・GL互換・資源同期を共通コアへ集約し、ローダー固有処理とMinecraft版ごとの描画連携を分離します。将来は対象版・ローダー別にAFVをビルドします。現在の対応目標はNeoForge 1.21.1で、Fabric版・Forge版は設計上の展開先です。

## 開発

完全なJDK 21（`javac`を含む）を使用してください。

```sh
./gradlew build
python3 scripts/verify_artifact.py
./gradlew runClient
```

出力: `build/libs/AllForVulkan-1.21.1-neoforge-0.1.0-alpha.1.jar`。
開発クライアントではNeoForgeのOpenGL早期ウィンドウを無効にします。
別のMinecraftインスタンスでは、その `config/fml.toml` に
`earlyWindowControl = false` を設定してください。詳細は[開発手順](docs/DEVELOPMENT.md)を参照。
