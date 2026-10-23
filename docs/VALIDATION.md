# 初期移植の検証記録

日付: 2026-10-24。対象: AFV 0.1.0-alpha.1 / Minecraft 1.21.1 / NeoForge 21.1.228。

## ビルドと配布構造

完全なEclipse Temurin JDK 21で `./gradlew build` が通過。
Java 21 JREのみの環境ではMinecraftの再コンパイルができないため、JDKが必要。
Luna Mediumによる機械検証で、活動中のJavaソースにFabricクラスへの依存がないこと、
NeoForgeメタデータ・Mixin設定・14個のネストされた依存JARの整合性を確認した。

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
画面のピクセル比較・ワールド描画・他OS/GPU・配布JARを実ランチャーに入れた起動は
別の検証が必要。

## 起動で修正した移植点

- NeoForgeの変更後のウィンドウ生成経路とシェーダーコンストラクター。
- GLFW/設定の初期化スレッド、開発時のcore-api読み込み。
- LWJGLの拡張列挙に必要なスタックサイズ。
- NeoForgeのリソースローダーに対応したshader include読み込み。
- shadercのinclude結果をreleaseコールバックまで保持し、コンパイル結果を解放。
- NeoForge側で追加された引数を持つパーティクル描画フック。
- ブートストラップ層のLWJGL置換に頼らない、Minecraft内部のFBO破棄等の接続。

## 未達の項目

M0全体の合格宣言はまだ行わない。ワールド、resize、再読込、Iris比較構成が必要。
Mesa/Zinkとの接続・外部メモリ／セマフォ共有・Iris互換性は未実装。
直接LWJGL/JNIを呼ぶMODの包括的な捕捉も未実装。
