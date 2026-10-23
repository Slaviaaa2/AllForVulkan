# Source provenance

All For Vulkan is a fork of xCollateral/VulkanMod, licensed under LGPL-3.0.
The complete upstream Git history and LICENSE are retained.

Initial source: tag `0.5.5`, commit `f23855f2675f1efb52fb8aa2c26bc23dccfb5543`
(Minecraft 1.21.1). Newer numbered upstream tags target other Minecraft versions;
no source claim is made for the separately published `0.6.7+1.21.1` binary.

AFV changes: NeoForge build/metadata/bootstrap, loader service boundary,
NeoForge terrain and fluid rendering, access transformers and packaging,
shader resource loading/callback lifetimes, and Minecraft internal GL routing.
Upstream Java packages and `vulkanmod` asset namespace are intentionally retained
for provenance and resource compatibility. The installed mod ID is `allforvulkan`.

`render/model/quad/NormalHelper.java` retains its original FabricMC Apache-2.0
copyright and license notice. Fabric renderer API sources are removed from this
NeoForge target; Fabric-specific upstream sources remain recoverable in Git.

The LWJGL native packaging approach was informed by VulkanMod-Reforged commit
`0618965fbedb71ca2ee2e5d4528fcedc4fb27551`, build.gradle. Reforged is a reference,
not an AFV runtime dependency. LWJGL libraries retain their own license notices.
