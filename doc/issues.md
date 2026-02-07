# Issues

Issues are stored as individual JSON files in the `issues/` directory. The filename serves as the issue ID (e.g., `commander-zone-gy-exile-layout.json`).

Closed issues should be deleted, not marked as closed.

## Format

```json
{
  "title": "Short summary of the issue",
  "description": "Full description with context...",
  "status": "open",
  "priority": 3,
  "type": "task",
  "labels": ["streaming-client"],
  "created_at": "2026-02-07T00:00:00.000000-08:00",
  "updated_at": "2026-02-07T00:00:00.000000-08:00"
}
```

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `title` | string | Short summary |
| `description` | string | Full description with context |
| `status` | string | Always "open" (delete closed issues) |
| `priority` | int | 1 (highest) to 4 (lowest) |
| `type` | string | Usually "task" |
| `labels` | string[] | Tags like "streaming-client", "ui", "puppeteer" |
| `created_at` | string | ISO 8601 timestamp |
| `updated_at` | string | ISO 8601 timestamp |

## Querying

### List all issues

```bash
ls issues/
```

### View an issue

```bash
jq . issues/commander-zone-gy-exile-layout.json
```

### List all issue titles with priority

```bash
for f in issues/*.json; do echo "$(basename "$f" .json): $(jq -r '[.priority, .title] | @tsv' "$f")"; done | sort -t$'\t' -k1 -n
```

### Find issues by label

```bash
for f in issues/*.json; do
  jq -e '.labels | index("streaming-client")' "$f" >/dev/null && basename "$f" .json
done
```

### Find high priority issues (priority 1-2)

```bash
for f in issues/*.json; do
  jq -e '.priority <= 2' "$f" >/dev/null && echo "$(basename "$f" .json): $(jq -r .title "$f")"
done
```

### Search descriptions

```bash
grep -l "streaming" issues/*.json
```
