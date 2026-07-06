# Product

## Register

product

## Users

The primary user is the single owner of the connector: someone with Android Health Connect data who wants Claude to read, analyze, and selectively write health records through a private backend. They use the Android app mostly for setup, sync confidence, and quick daily context rather than long analytic sessions.

## Product Purpose

Health Connector bridges on-device Health Connect data to a server-backed MCP connector for Claude. The Android app grants permissions, configures the backend, monitors sync health, and surfaces a concise daily Home view so the user can understand today at a glance and quickly log follow-up entries with Claude.

## Brand Personality

Quiet, trustworthy, and practical. The product should feel like private infrastructure with a friendly daily surface: calm enough for personal health data, direct enough for troubleshooting, and never like a marketing dashboard.

## Anti-references

Avoid decorative wellness-app tropes, oversized hero sections, gamified streak pressure, vague AI magic copy, and dense engineering-first screens as the default user experience. Setup details should stay available without making the Home screen feel like a debug panel.

## Design Principles

- Show only the few signals that help the user act today.
- Keep the phone client thin; server-computed values should be presented clearly rather than reinterpreted.
- Make setup and sync state easy to find, but secondary to the daily Home flow.
- Prefer system-native Material behavior, dynamic color, and accessible defaults over custom visual novelty.
- Treat health data as private and practical: no hype, no false precision, no unexplained claims.

## Accessibility & Inclusion

Use Material 3 defaults for contrast, sizing, touch targets, focus behavior, and reduced-motion compatibility where available. Motion should be subtle and nonessential, with content remaining readable in light, dark, and dynamic color schemes.
