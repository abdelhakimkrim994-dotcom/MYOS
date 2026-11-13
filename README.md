# MYOS

my phone my apps

MYOS is a personal Android app ecosystem: a collection of independently installable apps that share a coherent experience and can gradually replace parts of the phone's existing software.

The first app is **MYOS Home**, a custom Android launcher.

## Project principles

- Build incrementally, with small milestones that can be tested on a real Android device.
- Prefer native Kotlin/Android APIs for launcher and system-level capabilities.
- Use HTML, CSS, JavaScript, and WebView where they are a good fit.
- Keep apps independently installable while allowing shared design and core components.
- Preserve understandable, maintainable code. The project should be learnable and modifiable, not just generated.
- Record verified progress and distinguish working features from ideas or prototypes.

## First milestone: MYOS Home

The initial launcher prototype will establish:

1. A valid Android HOME activity that can be selected as the default launcher.
2. A minimal, peaceful home screen based on the project's chosen beach-and-ocean visual reference.
3. A swipe-up gesture that opens an ocean-inspired app drawer.
4. Discovery, display, and launching of installed apps.
5. A predictable way to close the drawer and return home.

The agreed visual and interaction direction is documented in [MYOS Home Design](docs/MYOS_HOME_DESIGN.md).

## Current status

This repository is at the documentation and planning stage. No Android application, APK, or functioning launcher is claimed to exist yet.

## Development approach

Implementation decisions such as minimum Android version, target SDK, device compatibility, and project structure should be made after checking the target phone and the relevant Android requirements. Each milestone should be built, installed, and verified before being treated as complete.
