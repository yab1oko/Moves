<h1 align="center">Moves</h1>
<p align="center"><b>Parkour movement for adventure maps — stamina, climbs, slides, dodges, and ziplines.</b></p>

<p align="center">
<code>Minecraft 1.20.1</code> · <code>Forge & Fabric</code> · <code>Client & Server</code> · <code><a href="https://github.com/yab1oko/Moves">GitHub</a></code>
</p>

---

## ✨ Overview
Moves is a parkour toolkit for Minecraft 1.20.1 on both Forge and Fabric. It adds stamina-backed movement meant for adventure maps and survival worlds where climbing, hanging, and short traversals matter more than vanilla sprint jumps alone.

## 🏃 Features
A shared stamina pool fuels sprinting and special moves. You can grab ledges, climb chains, fences, and bars, hang from ceilings, pull up, slide, dodge, and crawl.

<p align="center">
<img src="https://media.forgecdn.net/attachments/2166/625/hang-png.png" alt="Ledge hang" width="400">
<img src="https://media.forgecdn.net/attachments/2166/639/moves_climb_trimmed-gif.gif" alt="Climb and pull-up" width="320">
</p>

Pickaxe climb lets you chip into climbable walls when the right tool is equipped.

<p align="center">
<img src="https://media.forgecdn.net/attachments/2166/640/moves_pickaxes_trimmed-gif.gif" alt="Pickaxe climb" width="360">
</p>

Rope and chain ziplines carry you across gaps, with optional tool grip for a lighter stamina cost. Hang signs, lanterns, and banners on the line as decor.

<p align="center">
<img src="https://media.forgecdn.net/attachments/2166/641/moves_zipline_trimmed-gif.gif" alt="Zipline ride" width="420">
<img src="https://media.forgecdn.net/attachments/2166/622/zipline-decor-png.png" alt="Zipline with hanging decor" width="420">
</p>

Most systems can be toggled or tuned: costs, drains, regen, and feature switches live in `config/moves.json` and apply while the world is running. The same options are available from the Mods menu Config button (Forge, and Fabric with Mod Menu).

## 🎮 How to use
Bind Dodge, Grab, Prone, and Slide in Controls, then use them in-world. Open Mods → Moves → Config to toggle features and edit costs, or change `config/moves.json` / use `/moves` for the same values.

## More mods by yab1oko
- [Cold Weapons](https://www.curseforge.com/minecraft/mc-mods/cold-weapons)
- [Custom Loot System (CTS)](https://www.curseforge.com/minecraft/mc-mods/custom-loot-system)
- [YBLK Fashion](https://www.curseforge.com/minecraft/mc-mods/yblk-fashion)
- [YBLK Features](https://www.curseforge.com/minecraft/mc-mods/yblk-features)
- [Roleplay Materials](https://www.curseforge.com/minecraft/mc-mods/roleplay-materials)

## Feedback
Bugs, balance notes, and ideas: join the Discord — [yab1oko mods](https://discord.gg/tXMBWwSuRp).

## License
[MIT](LICENSE) — free to use, modify, and redistribute. Keep the copyright notice.

## Build
Java 17+. From the project root:

```bat
gradlew.bat :forge:build
gradlew.bat :fabric:build
```

Jars land in `forge/build/libs` and `fabric/build/libs`.

## Config
`config/moves.json`, the Mods menu **Config** button (Forge), or `/moves` in-world.
Costs list their defaults; change any value without restarting.
