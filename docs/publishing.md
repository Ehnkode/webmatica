# Publishing

Every published GitHub release builds one jar per Minecraft version, attaches them to the
release and, once set up, uploads them to Modrinth and CurseForge (job `publish` in
`.github/workflows/build.yml`, using [mc-publish](https://github.com/Kir-Antipov/mc-publish)).
The Minecraft versions of each file come from its `fabric.mod.json`, the changelog from the
release text, the release type from the tag (`-alpha`, `-beta`, otherwise release).

## One-time setup

1. **Modrinth:** create the project (Mod, client side required, server side unsupported,
   loader Fabric, license LGPL-3.0-only, source `https://github.com/Ehnkode/webmatica`).
   Copy its project ID (Settings → General). Create a personal access token with the scope
   "Create versions" (Settings → PATs).
2. **CurseForge:** create the project (Minecraft → Mods, license LGPL-3.0). Its project ID is in
   the "About Project" box. Create an API token (Account → API tokens).
3. **GitHub** (repository → Settings → Secrets and variables → Actions):
   - secrets `MODRINTH_TOKEN` and `CURSEFORGE_TOKEN`
   - variables `MODRINTH_ID` and `CURSEFORGE_ID`

Without a token the platform is skipped; without both the job does nothing.

## A release

1. Set `mod.version` in `stonecutter.properties.toml`.
2. Create a release on GitHub with the tag `v<version>-alpha` (or without `-alpha`) and the
   changes as text. Publishing it starts the upload.

## Listing text

Short description (Modrinth summary, CurseForge summary):

> Your Minecraft world in the browser: map, 3D view, chest search and schematics, plus an
> in-game menu and remote control for Litematica and Baritone. Client only, works on any server.

The long description can be the README without the build section. Pictures: `docs/*.gif`,
`docs/village.jpg`. Icon: `src/main/resources/assets/webmatica/icon.png`.
