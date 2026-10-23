# All For Vulkan 調査記録

調査日: 2026-10-24。公式ドキュメント、各作者のソース、Modrinth APIと公開JARのメタデータを使用。
起動・描画・速度の実測は行っていない。ブランチの内容と公開バイナリは一致すると仮定しない。
方針更新: ユーザー指定により、本家VulkanModを直接フォークしてNeoForge 1.21.1へ移植する。以下の既存NeoForge版は参考資料として扱い、開発基盤への必須依存にはしない。

## 確認した事実

1. **ZinkはDesktop OpenGLをVulkan上で実装するMesaのGalliumドライバー。** 利用可能なGL機能はZink実装とGPUのVulkan機能の両方に依存する。広いGL互換性の基盤候補になるが、VulkanMod接続用のAPIを提供するという意味ではない。[Mesa Zink](https://docs.mesa3d.org/drivers/zink.html)
2. **Mesa EGLにはコンテキストを用意するための基盤がある。** 実際に必要なplatform、profile、外部メモリ拡張、Zinkとの組合せは試作で確認が必要。[Mesa EGL](https://docs.mesa3d.org/egl.html)
3. **LWJGL GLはコンテキストごとの関数ポインタとthread-localなGLCapabilitiesを持つ。** 独自FunctionProviderの入口もある。ライブラリ名だけの差し替えで、GLFWコンテキストやMOD独自JNI経路まで解決するわけではない。[LWJGL GL](https://javadoc.lwjgl.org/org/lwjgl/opengl/GL.html)
4. **外部メモリと同期は別の仕組み。** OSごとのhandle対応も必要。同じGPUを使うことと、別VkDeviceで同じ資源を安全に使うことは別問題。[Vulkan Guide](https://docs.vulkan.org/guide/latest/extensions/external.html)
5. **ANGLEの対象はOpenGL ES。** Desktop GLの汎用互換基盤としては不足があると判断した。[ANGLE公式](https://chromium.googlesource.com/angle/angle/+/main/README.md)
6. **GLSLコンパイラーだけではGL互換性は完成しない。** glslang/shadercはシェーダー処理に有用だが、GLの状態、資源、リンク情報、同期は別に必要になる。[glslang](https://github.com/KhronosGroup/glslang)、[shaderc](https://github.com/google/shaderc)

## Minecraft側で確認したもの

| 候補 | 調査時の情報 | 計画への影響 |
| --- | --- | --- |
| 本家VulkanMod | 公式READMEはFabric導入を案内。Modrinthの1.21.1用に`0.6.7+1.21.1`が存在 | NeoForge用とは区別する |
| VulkanMod Reforged | 1.21.1用`0.3.5-ALPHA`公開。ソース`0618965fbedb71ca2ee2e5d4528fcedc4fb27551`はMC 1.21.1 / NeoForge 21.1.228指定 | NeoForge移植差分の参考。実ビルドとJAR対応は未検証 |
| VulkanModNeoForge | 1.21.1用公開版は`0.5.5-dev+3.1`。調査時のdefault `1.21neo_new`先`1a7b4b4e33100e14b5a0e3300e33c8ad019223b6`はMC 1.21.4設定 | 公開対象とdefault branchの食い違いに注意 |
| Iris NeoForge | 1.21.1向けrelease `1.8.12+1.21.1-neoforge`、beta `1.8.14-beta.1+1.21.1-neoforge`が存在 | Fabric版の依存情報をそのまま適用しない |

参照: [本家VulkanMod](https://github.com/xCollateral/VulkanMod)、[Reforged](https://github.com/TrulyRin/VulkanMod-Reforged)、[Reforged設定](https://github.com/TrulyRin/VulkanMod-Reforged/blob/0618965fbedb71ca2ee2e5d4528fcedc4fb27551/gradle.properties)、[VulkanModNeoForge](https://github.com/yiyuyan/VulkanModNeoForge)、[NeoForge版設定](https://github.com/yiyuyan/VulkanModNeoForge/blob/1a7b4b4e33100e14b5a0e3300e33c8ad019223b6/gradle.properties)。

版情報取得先: [Reforged 1.21.1 API](https://api.modrinth.com/v2/project/vulkanmod-reforged/version?game_versions=%5B%221.21.1%22%5D)、[NeoForge版 1.21.1 API](https://api.modrinth.com/v2/project/vulkanmodneoforge/version?game_versions=%5B%221.21.1%22%5D)、[Iris NeoForge 1.21.1 API](https://api.modrinth.com/v2/project/iris/version?game_versions=%5B%221.21.1%22%5D&loaders=%5B%22neoforge%22%5D)。

Iris `1.8.12`公開JARをメモリ上で開いて、`META-INF/neoforge.mods.toml`にSodium必須があることを確認した。
このJARにはMinecraft範囲`[1.21,1.21.1)`、内部versionにsnapshot/local表記も見られた。配布ページの版表示だけを信用して基準を固定せず、M0で実際のloader挙動とソース対応を確認する。
当該ファイル: `iris-neoforge-1.8.12+mc1.21.1.jar`、Modrinth version ID `t3ruzodq`、SHA-1 `a3e6355915c7d3b2bc392724795113e51d289378`。[版メタデータ](https://api.modrinth.com/v2/version/t3ruzodq)

Iris `1.21.1`ブランチの調査時commitは`eb7afb99f747cc8ed5ee4072119539035d33cefd`。
`IrisRenderSystem`はGLxxC、ARB、機能照会、compute等を使い、Sodium互換mixinはSodiumのchunk rendererに接続している。
これは公開`1.8.12`と同一ソースだという確認ではない。GL対応に加え描画統合が必要だという構造上の根拠として扱う。
[IrisRenderSystem](https://github.com/IrisShaders/Iris/blob/eb7afb99f747cc8ed5ee4072119539035d33cefd/common/src/main/java/net/irisshaders/iris/gl/IrisRenderSystem.java)、[chunk mixin](https://github.com/IrisShaders/Iris/blob/eb7afb99f747cc8ed5ee4072119539035d33cefd/common/src/main/java/net/irisshaders/iris/compat/sodium/mixin/MixinDefaultChunkRenderer.java)。

本家VulkanMod `0.5.5`のソースとNeoForge移植側には、LWJGLのGL11/GL30等を置換する既存mixinがある。
NeoForge移植側ではGLFWウィンドウを`GLFW_NO_API`で作る処理も確認した。
したがって、MesaのGLコンテキスト・資源IDと既存mixinが競合する箇所の調整が必要と推測する。独自フォークでは該当処理を直接整理する。
[GL11 mixin](https://github.com/yiyuyan/VulkanModNeoForge/blob/1a7b4b4e33100e14b5a0e3300e33c8ad019223b6/src/main/java/net/vulkanmod/mixin/compatibility/gl/GL11M.java)、[Window mixin](https://github.com/yiyuyan/VulkanModNeoForge/blob/1a7b4b4e33100e14b5a0e3300e33c8ad019223b6/src/main/java/net/vulkanmod/mixin/window/WindowMixin.java)。

## 類似プロジェクトとの関係

- **Beryl**: VulkanMod本家READMEが案内するVulkanシェーダーMOD。調査時の作者説明では内蔵シェーダーパックを使い、Modrinth APIのsource URLは未設定。汎用GL互換層や既存Irisの代替として確認できたものではない。[作者ページ](https://modrinth.com/mod/beryl)、[API](https://api.modrinth.com/v2/project/beryl)
- **VulkanModNeoForge-with-Shaders-Engine**: 作者資料がscreen-spaceのIris形式ブリッジと説明し、OpenGL互換層とは区別している。パック読込や複数pass処理の参考候補だが、本件の汎用互換性を満たす根拠にはしない。[作者の設計資料](https://github.com/nakoulaf4/VulkanModNeoForge-with-Shaders-Engine/blob/1.21.1neo/SHADER_ENGINE.md)
- **Volcanic**: NeoForge 1.21.1向けのVulkanMod派生で、ネイティブshader pipelineを説明している。汎用GL互換性の実証は今回確認していない。[作者リポジトリ](https://github.com/NelWenn/Volcanic)

## 試作前に確定できないこと

- 選定Mesa/Zink版で、必要なGL外部メモリ・semaphore拡張と形式が実際に使えるか。
- VulkanModとの深度画像の共有、描画途中の相互呼出し、必要なキュー同期が成立するか。
- VulkanMod側だけの変更で済むか、Mesa/Zinkパッチも必要になるか。
- 未改造Irisで成立するか、専用バックエンド/フォークが必要か。
- 配布ネイティブのサイズ、初期化順序、JNI依存、OS別サポート範囲と性能。

これらは次の実装M0/M1の具体的な検証項目であり、今回の文書では解決済みと扱っていない。
