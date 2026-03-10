-- Mechanoid PostgreSQL Schema
-- Reference DDL for use with external schema migration tools (Flyway, Liquibase, etc.)
-- The runtime uses PostgresSchema.initialize / PostgresSchema.createIfNotExists to manage
-- schema creation programmatically via Saferis, but this file documents the expected structure.

-- ============================================
-- Event Sourcing Tables
-- ============================================

CREATE TABLE IF NOT EXISTS fsm_events (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY NOT NULL,
  instance_id   VARCHAR(255) NOT NULL,
  sequence_nr   BIGINT NOT NULL,
  event_data    JSONB NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL,

  CONSTRAINT uq_fsm_events_instance_seq UNIQUE (instance_id, sequence_nr)
);

CREATE INDEX idx_fsm_events_instance ON fsm_events (instance_id, sequence_nr);

CREATE TABLE IF NOT EXISTS fsm_snapshots (
  instance_id   VARCHAR(255) PRIMARY KEY NOT NULL,
  state_data    JSONB NOT NULL,
  sequence_nr   BIGINT NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL
);

-- ============================================
-- Durable Timeout Table
-- ============================================

CREATE TABLE IF NOT EXISTS scheduled_timeouts (
  instance_id   VARCHAR(255) PRIMARY KEY NOT NULL,
  state_hash    INTEGER NOT NULL,
  sequence_nr   BIGINT NOT NULL,
  deadline      TIMESTAMPTZ NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL,
  claimed_by    VARCHAR(255),
  claimed_until TIMESTAMPTZ
);

CREATE INDEX idx_timeouts_deadline ON scheduled_timeouts (deadline);

-- ============================================
-- Distributed Lock Table
-- ============================================

CREATE TABLE IF NOT EXISTS fsm_instance_locks (
  instance_id   VARCHAR(255) PRIMARY KEY NOT NULL,
  node_id       VARCHAR(255) NOT NULL,
  acquired_at   TIMESTAMPTZ NOT NULL,
  expires_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_locks_expired ON fsm_instance_locks (expires_at);

-- ============================================
-- Leader Election Leases Table
-- ============================================

CREATE TABLE IF NOT EXISTS leases (
  key           VARCHAR(255) PRIMARY KEY NOT NULL,
  holder        VARCHAR(255) NOT NULL,
  expires_at    TIMESTAMPTZ NOT NULL,
  acquired_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_leases_expires ON leases (expires_at);
