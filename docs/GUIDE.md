# Smart Player Logger - Detailed Guide

A server-side Fabric mod for MC 26.3. See the [README](../README.md) for the
file formats and config table; this page covers behavior in detail.

## Installation

1. Install Fabric Loader and **Fabric API** on the server.
2. Drop the jar into `mods/` and start the server.
3. `P_Logger/` appears next to `server.properties`.

## Reconnect merging

When a player leaves, the leave line is written immediately (so a crash
loses nothing). If the same player joins again within
`reconnectThresholdMinutes`, that leave line is **removed** and a single
`reconnected (Left: ..., Return: ...)` line is **appended** at the end, in
place of a normal join line. Each such line counts as one line toward
`maxLines`. If the leave line has already been trimmed away, a normal join
line is written instead. Set `mergeReconnects` to `false` to always log
separate join/leave lines.

## Refused connections

The reason is read at the moment the server disconnects a client during
login. That covers whitelist, bans, full server, duplicate login, failed
authentication and login timeouts. Multi-line reasons (e.g. ban messages)
are flattened to one line with ` | `.

With `refusalDedupeSeconds` > 0, repeats of the same name + same reason within
that many seconds of the *first* attempt are folded into its line, which is
rewritten as `X was refused N times between <first> and <last>: reason`. The
next attempt after the window starts a new line. Different names or reasons
are never merged. Count updates are written at most once every 2 seconds.
Refusals go to `PA_Refusals.txt` unless `separateRefusalLog` is `false`. If a future Minecraft update renames
the hooked method, only this feature stops working (the rest keeps going).

## PA_History details

Entries are keyed by UUID, so name changes update the same line. While a
player is connected the line shows `Online`. On a clean shutdown everyone
online is recorded as having left. Lines marked `(estimated)` come from the
first-run import (or from an `Online` line left behind by a crash, which is
replaced by the file's last write time) and become exact as soon as the
player joins and leaves again.

## History sorting

`History_alphabetic` (default `true`) sorts `PA_History.txt` by name,
case-insensitive. With `false`, players currently online come first, then
everyone else by last-seen time, newest first (ties fall back to name).

## Performance

All file writes happen on one background thread; the server thread only
queues a tiny task. `PA_Logger.txt` is rewritten per event (a few KB);
`PA_History.txt` writes are coalesced to at most one per 2 seconds. Files
are written to a temp file and moved into place, so a crash can't leave a
half-written file.

## Building

`./gradlew build` inside `26.3/` with JDK 25. If Gradle does not pick up JDK 25 on its own, set
`org.gradle.java.home` in your user-level `~/.gradle/gradle.properties`.
