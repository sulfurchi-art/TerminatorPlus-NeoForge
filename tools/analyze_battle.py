#!/usr/bin/env python3
"""Read development JSONL logs without assuming that shots, projectile hits and damage events are equivalent."""
import argparse
import collections
import json
from pathlib import Path


def analyze(file):
    counts = collections.Counter()
    per_bot = collections.defaultdict(collections.Counter)
    heights = collections.defaultdict(list)
    stages = collections.defaultdict(list)
    summaries, warnings, latency = [], [], []
    previous_tick = None
    with file.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            try:
                event = json.loads(line)
            except (ValueError, TypeError) as error:
                warnings.append(f"Line {line_number}: {error}")
                continue
            kind = event.get("type", "unknown")
            counts[kind] += 1
            bot = event.get("bot")
            if bot:
                per_bot[bot][kind] += 1
            tick = event.get("tick")
            if tick is not None:
                if previous_tick is not None and tick < previous_tick:
                    warnings.append(f"Line {line_number}: tick went backwards")
                previous_tick = tick
            if kind == "log_gap":
                warnings.append(f"Queue overflow: {event.get('dropped')} events lost")
            if kind == "summary":
                summaries.append(event)
            if kind == "drone_sample":
                drone = event.get("drone") or {}
                heights[drone.get("uuid", "unknown")].append({k: event.get(k) for k in ("tick", "height", "vy", "mode")})
            if kind == "drone_attack":
                latency.append({k: event.get(k) for k in ("bot", "firstAttackLatencyTicks", "horizontalDistance", "round", "kind")})
                if event.get("firstAttackLatencyTicks", 0) > 100:
                    warnings.append(f"Drone first attack exceeds 100 ticks: {bot}")
            if kind.startswith("c4_") or kind.startswith("shelter_"):
                stages[bot].append(event)
                if kind == "c4_end" and event.get("orbitTicks", 0) > 80:
                    warnings.append(f"C4 orbit exceeds 80 ticks: {bot}")
                if kind == "shelter_done" and event.get("blocks", 0) > 4:
                    warnings.append(f"Shelter exceeds four blocks: {bot}")
    return {"file": str(file), "events": dict(counts), "per_bot": dict(per_bot), "drone_attacks": latency,
            "drone_height_samples": dict(heights), "stages": dict(stages), "summaries": summaries,
            "warnings": warnings, "potion_throw_count": counts["potion_throw"]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("file", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    text = json.dumps(analyze(args.file), ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(text + "\n", encoding="utf-8")
    else:
        print(text)


if __name__ == "__main__":
    main()
