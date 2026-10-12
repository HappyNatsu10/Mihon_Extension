# DeviantArt 403 Hardening & Regression Cases (`403_CASES.md`)

This document records the 403 error root causes, diagnostic classifications, and fixes per FR-11.

---

## 1. Classification & Remediation Matrix

| Scenario / Trigger | Observed Cause | Fix / Extension Behavior |
|--------------------|----------------|--------------------------|
| Modified CDN URL (e.g. stripped `/v1/...`) | Signed Wixmp tokens expire / fail HMAC verification | Preserves raw signed URLs with exact tokens without tampering |
| Cloudflare Bot Challenge | IP / user-agent flagged by Cloudflare challenge | Interceptor catches challenge HTML and prompts WebView login |
| Mature content without login | DeviantArt gates 18+ deviations behind user account | Explains mature restriction; user logs in once via WebView |
| Rate limiting (429 or 403) | Fast bursts against DA endpoints | Exponential backoff retry with jitter up to 3 times |
| Expired image token | Wixmp temporary CDN token expired | Mihon retries page list -> fetches fresh single deviation token |
