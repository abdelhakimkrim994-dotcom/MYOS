# MYOS Home — Design Brief

## Product direction

MYOS Home is the first app in the MYOS personal Android app ecosystem. It is a custom Android launcher, designed to feel calm, minimal, and personal rather than like a conventional busy home screen.

## Visual concept

The home screen is based on the user's chosen beach-and-ocean reference image:

- A clean, predominantly white upper area.
- A beautiful ocean wave or water scene covering the lower half.
- The exact user-provided reference image is the visual target. Do not substitute generic beach photography without approval.
- Keep the resting screen static and exceptionally minimal.
- No default clock, search bar, widgets, or app-icon clutter.
- A subtle “Swipe up to open” hint may be included if it does not compromise the composition.

## App drawer interaction

The selected concept is an ocean that rises to fill the screen:

1. The phone opens to the static beach home screen.
2. The user swipes upward.
3. A rising wave transition moves upward from the bottom, carrying the app icons into view.
4. The ocean expands until it fills the screen.
5. App icons appear to float above the water, with clear labels and generous spacing.
6. The open drawer remains static; no continuous water animation or drifting icons.
7. Swiping down closes the drawer and returns to the original beach screen.

The wave transition is the primary intentional motion. Its final shape, timing, and easing can be refined during prototyping.

## Icon presentation

- Floating icons, not a conventional rigid grid and not islands.
- Maintain legible labels, comfortable spacing, and Android-appropriate touch targets.
- Prioritize usability and predictable app launching over visual effects.

## Behavior and acceptance criteria for the first prototype

- Can be selected as the device's default Android HOME launcher.
- Pressing Home returns to MYOS Home.
- Swipe up opens the app drawer with the rising-wave transition.
- Installed apps are discovered and can be launched.
- Swipe down closes the drawer.
- Android Back behavior is predictable.
- App names remain legible and touch targets are practical.
- The prototype is installed and tested on the user's actual phone.

## Technical guardrails

- Use native Kotlin and Android APIs for HOME intent handling, installed-app discovery, and app launching.
- Use the simplest appropriate rendering approach for the visual layer; keep the architecture modular.
- Determine minimum and target SDK after checking the target device and current Android requirements.
- Build in small milestones and verify each milestone on-device.
- Do not describe a feature as complete until it has been built and tested.

## Scope discipline

Keep the first version focused on the home screen, drawer transition, and launching installed apps. Defer search, favourites, folders, widgets, and other launcher conveniences until the core experience is working and usability has been tested.
