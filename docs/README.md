# CamSure

> Project handrail for the native Android-to-OBS multi-camera system.
> CamSure is the official product name.

## Project Goal

Build a native, low-latency Android camera system designed specifically for OBS Studio.

The system should let multiple Android phones act as independent live-production cameras and appear directly inside OBS as native sources.

Primary target:

- Up to **4 simultaneous Android cameras**
- Native **OBS source plugin**
- **Local network first**; internet connection must not be required
- **USB support** later, without making USB a prerequisite for the first working version
- **Hardware video encoding** on Android
- **Low-latency decoding** on the OBS PC
- Remote camera controls directly from OBS
- Capability-driven access to the phone's real camera features
- High image quality without treating the phone as a generic webcam
- Stable live-production behavior for church/event use

## Non-Goals for Early Development

Do not expand the first milestones into:

- a general-purpose virtual webcam driver
- browser-based streaming
- cloud relays
- account systems
- internet streaming infrastructure
- AI camera effects
- beauty filters
- background removal
- iOS support
- 4K/HDR before the basic transport is proven
- custom USB protocol before LAN streaming works
- a polished consumer app before the core pipeline is reliable

## Core Principle

The product is not:

> "A phone pretending to be a webcam."

The product is:

> "A software-defined multi-camera live-production system that uses Android phones as remotely controlled cameras for OBS."

## Start Here

Agents should read these files in this order:

1. `AGENTS.md`
2. `DECISIONS.md`
3. `ARCHITECTURE.md`
4. `ROADMAP.md`
5. `PROGRESS.md`
6. `TESTING.md`
7. `OPEN_QUESTIONS.md`

## File Responsibilities

| File | Purpose |
|---|---|
| `AGENTS.md` | Rules for Codex/AI agents working on the repository |
| `DECISIONS.md` | Architectural decisions that should not silently drift |
| `ARCHITECTURE.md` | Current system model and component boundaries |
| `ROADMAP.md` | Ordered development slices and acceptance gates |
| `PROGRESS.md` | Current implementation status and verified results |
| `TESTING.md` | Performance, latency, stability, and multi-camera test plan |
| `OPEN_QUESTIONS.md` | Unresolved design questions that need evidence before decisions |

## Active Phase Handoff

- [Transport-Neutral Decode → OBS implementation report](DECODE_OBS_REPORT.md)
  — implemented, host-tested and Samsung physical baseline accepted with limitations.

- [Phase 4 — One Encoded Camera Stream over LAN](PHASE_4_HANDOFF.md)

Earlier phase handoffs remain alongside the phase they describe. A handoff
defines a bounded implementation slice; it does not replace the open questions
or record a permanent architecture decision by itself.

## Change Discipline

Whenever an implementation changes an architectural assumption:

1. Update `DECISIONS.md`.
2. Update `ARCHITECTURE.md`.
3. Record the result in `PROGRESS.md`.
4. Add or update the relevant test in `TESTING.md`.

Do not let implementation become the only source of truth.
