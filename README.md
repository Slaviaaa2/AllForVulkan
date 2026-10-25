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

VulkanModタグ `0.5.5`（`f23855f2675f1efb52fb8aa2c26bc23dccfb5543`）からNeoForge **21.1.228** へ移植し、タイトル・ワールド・地形／流体・リサイズ・リソース再読込・切断を実機で検証しています。NeoForgeでは効かないJOMLのMixin依存を廃し、投影行列をVulkanへ渡す境界で深度範囲を変換しました。Linux / RTX 3060ではホットバー・照準・通知・手元の表示もクライアント範囲の画像で確認しています。描画全体の画素一致や、全GPUでの動作確認を意味しません。

MOD一覧のAFV設定から **「自動」「Vulkan」「OpenGL」** を選択できます。変更は再起動後に適用します。起動前にVulkan 1.2・必要な機能／拡張・論理デバイス作成を調べ、使えない場合はAFVの描画Mixinを適用せずOpenGLで起動します。起動途中の異常終了時も次回はOpenGLで復旧します。他のMODがある場合、「自動」「Vulkan」は下記のLinux向けZinkクライアント経路を検査し、未対応時はOpenGLを選びます。既知のIris/Sodiumだけでなく、未知のMODでも既存のGL描画処理を保持するための方針です。

**Zinkクライアント経路**は、Minecraft・Iris・Sodiumの実OpenGL呼び出しをMesa/Zinkで実行し、最終default framebufferを外部メモリと双方向GPUセマフォでAFV所有のVulkanデバイスへ渡して表示します。画面全体のCPU転送は行いません。GL資源・MRT・影・シェーダーpassは既存のIris/Sodiumと実GLドライバーが管理します。この経路ではAFV独自の地形レンダラーを使用しません。Linuxとインストール済みMesa EGL/Zinkが必要です。別プロセスで実GL 4.6・compute・画像共有と、同じGPUの実surface／swapchain作成を検査し、検査の失敗・異常終了・タイムアウト時はOpenGLを選びます。ドライバー内部のクラッシュや全環境での無事故を保証するものではありません。

実機検証したZinkクライアントの組合せ（Linux／RTX 3060／NeoForge 21.1.251／専用X、指定Complementary Reimagined r5.9.3）：

| Iris | Sodium | MOD構成 | 確認したシナリオ |
| --- | --- | --- | --- |
| 1.8.12 | 0.6.13 | 最小2 MOD＋AFV | ワールド、Iris切替・再読込、リサイズ、ウィンドウ復帰、3ディメンション往復、GPU出力照合 |
| 1.8.14-beta.1 | 0.8.13 | DTFuel 0.1.10全204 MOD＋AFV | ワールド、Iris切替・再読込、リサイズ、ウィンドウ復帰、GPU出力照合 |

版・JARチェックサム・試験条件は[検証記録](docs/VALIDATION.md)に記載しています。

性能実験として、JVM引数 `-Dafv.experimentalAsyncPresent=true` でVulkan表示処理を専用スレッドへ移し、その間に次のGLフレームを進められます。Zinkクライアント経路用で、標準はOFFです。同一JARのOFF／ON測定と試験条件は[検証記録](docs/VALIDATION.md)を参照してください。

さらに `-Dafv.experimentalFramePipeline=true`（検証launcherでは `--frame-pipeline`）で、画像取得・転送・表示をまとめて専用スレッドへ渡し、3枚の共有画像でGL描画を先行させる実験を選べます。この設定は非同期表示も含みます。未完了jobは既定で1件、`-Dafv.experimentalPipelineDepth=1|2|3`（`--pipeline-depth`）で比較できます。標準はOFFで、比較測定には描画FPSと実際に完了したVulkan表示数の両方を記録します。

Linux向け実験機能 `-Dafv.zinkInterop=true` は別の経路で、AFV独自の地形描画からワールドの色と深度をGPU共有画像でZinkへ渡し、GLSL後処理からVulkanへ戻します。処理位置はワールド描画後・手元の深度クリアとGUI描画の前です。`-Dafv.zinkEffect=invert` / `depth` で診断用の色反転／深度表示を選べます。こちらの経路にIrisの地形・影・複数passを統合する機能は開発中です。**Iris全機能・全シェーダーパック・全GPUの完全互換は未確認**です。

- [ビルド・起動方法と現状](docs/DEVELOPMENT.md)
- [検証記録](docs/VALIDATION.md)
- [ソースの由来とライセンス](NOTICE.md)
- [開発計画](PLAN.md)
- [共通コア・マルチローダー設計](ARCHITECTURE.md)
- [調査記録・技術資料](RESEARCH.md)
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
