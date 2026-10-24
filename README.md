# All For Vulkan

**All For Vulkan (AFV)** は、VulkanMod本家のフォークを基に開発する、Minecraft **1.21.1 / NeoForge** 向けの統合描画MODです。

VulkanMod由来のVulkan描画本体に、OpenGL互換機能とMOD連携を組み込み、Irisを含む様々なMODの描画をVulkan上で動かすことを目指します。GL変換エンジンにはMesa/Zinkの採用を検討しています。

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

MOD一覧のAFV設定から **「自動」「Vulkan」「OpenGL」** を選択できます。変更は再起動後に適用します。起動前にVulkan 1.2・必要な機能／拡張・論理デバイス作成を調べ、使えない場合はAFVの描画Mixinを適用せずOpenGLで起動します。起動途中の異常終了時も次回はOpenGLで復旧します。Iris/Sodium/Embeddiumがある場合も現在はOpenGLを選びます。これは**Vulkan上でのIris対応を意味しません**。ドライバー内部のクラッシュや全環境での無事故を保証するものではありません。

Linux向け実験機能 `-Dafv.zinkInterop=true` は、ワールドの色と深度をGPU共有画像でZinkへ渡し、GLSL後処理からVulkanへ戻します。処理位置はワールド描画後・手元の深度クリアとGUI描画の前です。`-Dafv.zinkEffect=invert` / `depth` で診断用の色反転／深度表示を選べます。CPU assetのテクスチャIDも実GL名で共有します。通常は無効です。汎用GLのbuffer／FBO／MRT・GPU更新資源の同期・Irisの地形／影／複数pass統合は開発中です。AFVなしのIris＋Sodium比較環境では実IrisRenderingPipelineの起動と再読込を確認していますが、**AFVのVulkan描画とIrisを同時に動かす実装は未完了**です。

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
