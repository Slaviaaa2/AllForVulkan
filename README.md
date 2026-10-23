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

現在は **M0（NeoForge移植）の初期実装**です。本家タグ `0.5.5`（`f23855f2675f1efb52fb8aa2c26bc23dccfb5543`）を起点とし、NeoForge **21.1.228** 向けにビルド・メタデータ・起動順序・地形／流体描画を移植しました。`core-api` を独立したJavaモジュールとして追加しています。描画本体の共通コアへの抽出はこれからです。Linux / RTX 3060でタイトル画面の60 tickスモークテストと正常終了を確認しました。ワールド内の地形描画命令、リサイズ、リソース再読込、切断の自動検証も追加・通過しました。描画全体のピクセル比較は未実施です。

**汎用OpenGL互換、Iris統合は未実装**です。`-Dafv.zinkInterop=true` で有効にするLinux向け診断では、AFV本体の描画を共有画像でZinkへ渡し、GLSLの後処理を通してVulkanの表示へ戻します。`-Dafv.zinkEffect=invert` で色反転も試せます。smoke testの数値検査は小領域に限定します。通常は無効です。CPUから読み込むテクスチャ・アトラス・NativeImageは、同じ実GL名でZink側にも転送する経路を追加しました。フレーム色画像の往復を実装しましたが、任意の描画先・深度・直接GLで書き換えた資源の双方向統合はまだ必要です。独立した `native-bridge` 試作では、実際のZink GL 4.6/GLSL 4.60、GPU UUID照合、外部メモリFDと双方向セマフォによる画像共有を確認しました。AFVを載せないIris＋Sodium比較環境では、通常GL／Zinkそれぞれでテスト用シェーダーパックの起動・ワールド・リサイズ・再読込が通過しています。現時点では実験用の移植版で、Iris/Sodiumとの同時導入には対応していません。

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
