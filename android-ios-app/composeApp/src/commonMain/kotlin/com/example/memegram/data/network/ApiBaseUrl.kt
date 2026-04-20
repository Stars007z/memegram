package com.example.memegram.data.network

/**
 * Base URL for the Memegram orchestrator API.
 *
 * - Android emulator: http://10.0.2.2:8000  (10.0.2.2 = host loopback in AVD)
 * - iOS simulator:    http://localhost:8000 (simulator shares host network)
 * - Real devices / production: override at build time or replace this constant.
 *
 * Switch to your prod URL (e.g. "https://memegram.win") when shipping a release.
 */
expect val apiBaseUrl: String

/**
 * Base URL for the standalone NSFW moderation service (FastAPI, port 8001).
 * Same hostname rules as [apiBaseUrl] apply.
 */
expect val nsfwBaseUrl: String
