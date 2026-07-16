"""
Minimal eval harness for the Semantic Parser pass (Vision.md section 7.8).

Runs each (utterance -> expected) case in cases.json through the real
llama.cpp binary with grammar-constrained decoding against
structured_action_plan.schema.json, then checks:
  1. structural validity  -- does it parse as JSON and pass jsonschema validation?
  2. semantic correctness -- does `goal` match, are the expected slots non-null,
     does numeric_constraints have the expected length?

Exists so prompt tweaks / model swaps / quantization changes can be measured
against a fixed baseline instead of eyeballed, per Vision.md 7.8.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

import jsonschema

ROOT = Path(__file__).resolve().parents[2]
BIN = ROOT / "research/llama-verify/llama.cpp/build/bin/llama-completion.exe"
MODEL = ROOT / "models/qwen2.5-1.5b-instruct-q4_k_m.gguf"
SCHEMA_PATH = ROOT / "docs/structured_action_plan.schema.json"
SYSTEM_PROMPT_PATH = ROOT / "research/llama-verify/system_prompt_v3.txt"
CASES_PATH = Path(__file__).parent / "cases.json"

ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")


def extract_json(raw_stdout: str):
    text = ANSI_RE.sub("", raw_stdout)
    marker = "\nassistant\n"
    idx = text.rfind(marker)
    if idx == -1:
        return None
    tail = text[idx + len(marker):]
    brace_start = tail.find("{")
    if brace_start == -1:
        return None
    decoder = json.JSONDecoder()
    try:
        obj, _ = decoder.raw_decode(tail[brace_start:])
        return obj
    except json.JSONDecodeError:
        return None


def run_model(utterance: str) -> str:
    result = subprocess.run(
        [
            str(BIN),
            "-m", str(MODEL),
            "--json-schema-file", str(SCHEMA_PATH),
            "-sysf", str(SYSTEM_PROMPT_PATH),
            "-p", utterance,
            "-n", "400",
            "-cnv", "-st",
            "--temp", "0.2",
        ],
        capture_output=True,
        text=True,
        timeout=120,
    )
    return result.stdout + result.stderr


def check_case(case: dict, schema: dict) -> dict:
    raw = run_model(case["utterance"])
    parsed = extract_json(raw)

    outcome = {"id": case["id"], "utterance": case["utterance"], "pass": False, "notes": []}

    if parsed is None:
        outcome["notes"].append("FAIL: could not extract a JSON object from model output")
        return outcome

    try:
        jsonschema.validate(parsed, schema)
    except jsonschema.ValidationError as e:
        outcome["notes"].append(f"FAIL: schema violation: {e.message}")
        return outcome

    expect = case["expect"]
    ok = True

    if parsed.get("goal") != expect["goal"]:
        ok = False
        outcome["notes"].append(f"goal mismatch: expected {expect['goal']!r}, got {parsed.get('goal')!r}")

    slots = parsed.get("slots", {})
    for slot_name in expect.get("slots_present", []):
        if not slots.get(slot_name):
            ok = False
            outcome["notes"].append(f"expected slot '{slot_name}' to be populated, got null/missing")

    expected_count = expect.get("numeric_constraints_count")
    if expected_count is not None:
        actual_count = len(slots.get("numeric_constraints") or [])
        if actual_count != expected_count:
            ok = False
            outcome["notes"].append(
                f"numeric_constraints count mismatch: expected {expected_count}, got {actual_count}"
            )

    outcome["pass"] = ok
    outcome["parsed"] = parsed
    if ok:
        outcome["notes"].append("PASS")
    return outcome


def main():
    schema = json.loads(SCHEMA_PATH.read_text())
    cases = json.loads(CASES_PATH.read_text())

    results = []
    for case in cases:
        print(f"--- {case['id']} ---")
        outcome = check_case(case, schema)
        results.append(outcome)
        for note in outcome["notes"]:
            print(f"  {note}")
        print()

    passed = sum(1 for r in results if r["pass"])
    print(f"RESULT: {passed}/{len(results)} cases passed")

    out_path = Path(__file__).parent / "last_run_results.json"
    out_path.write_text(json.dumps(results, indent=2))
    print(f"Full results written to {out_path}")

    if passed < len(results):
        sys.exit(1)


if __name__ == "__main__":
    main()
