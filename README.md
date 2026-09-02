<a id="readme-top"></a>

<p align="center">
  <a href="https://github.com/StoryTime-Productions/PropHunt/actions/workflows/main.yml"><img src="https://github.com/StoryTime-Productions/PropHunt/actions/workflows/main.yml/badge.svg" alt="CI status" /></a>
  <img src="https://img.shields.io/badge/Paper-26.1.2-blue" alt="Paper 26.1.2" />
  <img src="https://img.shields.io/badge/Java-26-orange" alt="Java 26" />
</p>

PropHunt is a PaperMC plugin for the StoryTime SMP — a Prop Hunt minigame where hiders disguise themselves as blocks while hunters try to sniff them out before the timer runs out.

Contributions, ideas, or feature requests are always welcome!

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## What It Does

- **Hunt lobby**: a GUI (`/hunt lobby`) for picking a team (Hunters/Hiders), a class, a map to vote for, and readying up before a round starts.
- **Prop Hunt**: hiders disguise as blocks/entities and hide; hunters are locked in and blinded for a countdown, then released to hunt. A proximity-based heartbeat system warns hiders when a hunter closes in, plus a "recently hurt" tension alarm and an idle-spotlight system that briefly reveals hiders who camp one spot too long.
- **Hunter classes**: Brute (Shockwave — knocks back and reveals nearby disguised hiders), Nimble (Dash — a speed burst), Saboteur (Scanner — reveals disguised hiders in a radius) — distinct speed/damage modifiers, weapons, and a unique utility ability on a cooldown.
- **Hider classes**: Trickster (a melee hit locks the hunter in place instead of dealing damage), Phaser (teleports through a wall to the pocket behind it), Cloaker (brief invisibility, auto-restores the prior disguise after) — each with a unique ability, plus a shared block-disguise ability.
- **Imposter Hunt**: murderer/sheriff/innocent roles, a coin economy (pick up coins, buy a zapper), and zapper-weapon eliminations instead of team-based hide-and-seek.
- **Maps**: Castle, Mountain, Medieval, Industrial, and Office, each with configurable hunter/hider spawn points, voted on during the prep phase.
- **Disguises**: block disguises for hiders, plus 6 killer-model NPCs (Springtrap, Herobrine, Slenderman, Cryptid, Jigsaw, Scarecrow) hunters can disguise as via LibsDisguises, each with its own passive ability.

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/hunt` (aliases `huntlobby`, `huntgame`) | Opens the Hunt game lobby / teleports to the Hunt world spawn | `prophunt.hunt` (default: true) |
| `/huntgamemode` (aliases `huntmode`, `gamemode`) | Manage the Hunt gamemode (Prop Hunt or Imposter Hunt) | `prophunt.huntgamemode` (default: op) |
| `/stdisguise` | Manage entity disguises in the lobby | `prophunt.disguise` (default: op) |

`prophunt.*` grants all of the above permissions (default: false).

## Requirements

- Paper `26.1` (see `api-version` in `plugin.yml`; built against `paperDevBundle('26.1.2.build.+')`)
- Java 26 (toolchain), source/target compatibility 25
- [LibsDisguises](https://www.spigotmc.org/resources/libs-disguises-free.81/) (hard dependency)
- Optional soft-dependency: packetevents

## Getting Started (Developers)

### Prerequisites

- Gradle (or the included `gradlew` / `gradlew.bat` wrapper)
- Java 26: set `JAVA_HOME` accordingly

### Build

```
./gradlew build
```

Run `./gradlew spotlessApply` first to auto-format code (CI runs `spotlessApply` on PRs and expects clean formatting; it's skipped automatically when `CI` env var is set).

### Install

Drop the built shadow jar (from `build/libs/`) into your server's `plugins/` folder alongside LibsDisguises, then restart.

Additional local dev steps:

1. Configure map worlds, spawn points, and hologram locations in `src/main/resources/hunt.yml`.
2. Run `./set-hooks-path.sh` once to enable project-specific Git hooks.

## Configuration

- `src/main/resources/config.yml`: legacy join-block/game-area/exit-area/player-limit/ticket-cost settings.
- `src/main/resources/hunt.yml`: the primary configuration surface — Hunt world/spawn, disguise NPC locations, disguise/cooldown settings, hologram locations, prep-phase timing (countdown, game duration, hunter lock-in, heartbeat detection), per-map world/spawn definitions, hider ability cooldowns, and Imposter Hunt settings (roles, cooldowns, coins, ranges).

## CI/CD

GitHub Actions workflows under `.github/workflows/`:

- `pr.yml`: on pull requests to `main`: runs `spotlessApply`, then builds the plugin with Gradle and uploads build/test artifacts.
- `main.yml`: on push to `main` (and a weekly Saturday cron, to catch PaperMC upstream breakage): builds and tests, then runs a `release` Gradle task and uploads the release build.
- `commitlint.yml`: lints commit messages.
- `static.yml`: on push to `main` or manual dispatch: builds with Gradle (JDK 26 + 21), runs `./gradlew javadoc`, and deploys the generated Javadoc to GitHub Pages.
- `release.yml`: on a published GitHub release: posts a Discord notification (pre-release vs. full release, different webhooks) via `appleboy/discord-action`.
- `tag.yml`: on pushing a `v*` tag: runs `./gradlew release`, uploads build artifacts, and creates a draft GitHub Release (marked prerelease if the tag contains `-rc-`); a second job notifies Discord of the outcome.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). In short: fork the repo, create a feature branch, commit your changes, push, and open a pull request. Issues and feature requests are also welcome via the [issue templates](.github/ISSUE_TEMPLATE).

### Top contributors

<a href="https://github.com/StoryTime-Productions/PropHunt/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=StoryTime-Productions/PropHunt" alt="contrib.rocks image" />
</a>

## Roadmap

- [x] Prop Hunt core loop (lobby, prep phase, hunter/hider classes, disguises)
- [x] Imposter Hunt gamemode (roles, coin economy, zapper weapons)
- [ ] Public playtest release

See the [open issues](https://github.com/StoryTime-Productions/PropHunt/issues) for a full list of proposed features (and known issues).

## Contact

Nirav Patel - [@Niravanaa](https://github.com/Niravanaa) - niravp0703@gmail.com

StoryTime Productions: [Portfolio Link](https://storytime-productions.github.io/)

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## License

GNU General Public License v3.0 - see [LICENSE](LICENSE).
