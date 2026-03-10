# Mechanoid Documentation

A type-safe, effect-oriented finite state machine library for Scala 3 built on ZIO.

## Table of Contents

- [Overview](overview.md)
- [Core Concepts](core-concepts.md) - States, events, transitions
- [Defining FSMs](defining-fsms.md) - Compile-time safety, assemblyAll, timeouts, composition
- [Running FSMs](running-fsms.md) - Simple and persistent runtimes
- [Persistence](persistence.md) - Event sourcing, snapshots, recovery
- [Durable Timeouts](durable-timeouts.md) - TimeoutStore, sweepers, leader election
- [Distributed Systems](distributed.md) - Architecture, locking, combining features
- [Lock Heartbeat and Atomic Transitions](lock-heartbeat.md) - Lock renewal, atomic transitions
- [Side Effects](side-effects.md) - Entry effects, producing, fault-tolerant patterns
- [Visualization](visualization.md) - Mermaid and GraphViz output
- [Reference](reference.md) - Error handling, complete example, dependencies
