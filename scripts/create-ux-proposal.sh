#!/usr/bin/env bash
# create-ux-proposal.sh — create a Figma proposal card from a GitHub user story issue
#
# Usage: ./scripts/create-ux-proposal.sh <issue-number>
#
# Prerequisites:
#   - claude CLI installed (npm install -g @anthropic-ai/claude-code)
#   - gh CLI installed and authenticated
#   - Figma desktop app open with the Sushi file
#   - ANTHROPIC_API_KEY set (or claude already authenticated)

set -euo pipefail

ISSUE_NUMBER="${1:-}"

if [[ -z "$ISSUE_NUMBER" ]]; then
  echo "Usage: $0 <issue-number>"
  echo "Example: $0 42"
  exit 1
fi

REPO="hlan-net/sushi"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROMPT_FILE="$SCRIPT_DIR/prompts/ux-proposal-figma.md"

if [[ ! -f "$PROMPT_FILE" ]]; then
  echo "Error: prompt file not found at $PROMPT_FILE"
  exit 1
fi

echo "→ Checking issue #$ISSUE_NUMBER exists..."
if ! gh issue view "$ISSUE_NUMBER" --repo "$REPO" > /dev/null 2>&1; then
  echo "Error: issue #$ISSUE_NUMBER not found in $REPO"
  exit 1
fi

echo "→ Checking Figma desktop app is reachable via MCP..."
echo "  (Make sure the Sushi Figma file is open in the Figma desktop app)"
echo ""

# Build the prompt: inject the issue number so the agent knows which one to fetch
PROMPT=$(sed "s/ISSUE_NUMBER/$ISSUE_NUMBER/g" "$PROMPT_FILE")

echo "→ Running Claude agent to create Figma proposal card for issue #$ISSUE_NUMBER..."
echo "  This will: fetch the issue → inspect Figma → create card → post comment"
echo ""

claude -p "$PROMPT" \
  --allowedTools "Bash,mcp__plugin_figma_figma__use_figma,mcp__plugin_figma_figma__get_metadata,mcp__plugin_figma_figma__get_screenshot" \
  --output-format text

echo ""
echo "✓ Done. Check issue #$ISSUE_NUMBER for the Figma link comment."
