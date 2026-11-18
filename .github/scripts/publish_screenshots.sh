#!/usr/bin/env bash
# Publishes Roborazzi screenshots to the `screenshots` branch and (for PRs) posts/updates one PR comment
# showing the screens that changed versus main, plus the PR's 3-line summary.
#   usage: publish_screenshots.sh main            (push to main: refresh the baseline)
#          publish_screenshots.sh pr <number>     (pull request)
# Needs: GH_TOKEN, GITHUB_REPOSITORY, GITHUB_SHA; run from the repo root after recordRoborazziDebug.
set -euo pipefail
MODE="$1"; PR="${2:-}"
SRC="app/build/outputs/roborazzi"
REPO="$GITHUB_REPOSITORY"
DEST=$([ "$MODE" = "main" ] && echo "main" || echo "pr-$PR")

shopt -s nullglob
files=("$SRC"/*.png)
if [ ${#files[@]} -eq 0 ]; then echo "No screenshots found in $SRC"; exit 0; fi

git config --global user.name "github-actions[bot]"
git config --global user.email "41898282+github-actions[bot]@users.noreply.github.com"
WORK="$(mktemp -d)"
if git ls-remote --exit-code --heads origin screenshots >/dev/null 2>&1; then
  git fetch --depth=1 origin screenshots
  git worktree add "$WORK" FETCH_HEAD
  (cd "$WORK" && git checkout -B screenshots)
else
  git worktree add --detach "$WORK"
  (cd "$WORK" && git checkout --orphan screenshots && git rm -rqf --cached . >/dev/null && git clean -fdxq && printf '# Screenshots\nPublished by CI. Do not edit.\n' > README.md)
fi

# Which screens changed versus main (new or different bytes)?
changed=()
for f in "${files[@]}"; do
  name="$(basename "$f")"
  if [ ! -f "$WORK/main/$name" ] || ! cmp -s "$f" "$WORK/main/$name"; then changed+=("$name"); fi
done

rm -rf "${WORK:?}/$DEST"; mkdir -p "$WORK/$DEST"
cp "${files[@]}" "$WORK/$DEST/"
(cd "$WORK" && git add -A . && git commit -qm "Screenshots for $DEST ($GITHUB_SHA)" || true)
for i in 1 2 3; do
  if (cd "$WORK" && git push -q origin screenshots); then break; fi
  (cd "$WORK" && git fetch -q --depth=1 origin screenshots && git rebase -q FETCH_HEAD) || true
  sleep 3
done

[ "$MODE" = "main" ] && exit 0

# --- PR comment
body_file="$(mktemp)"
pr_body="$(gh api "repos/$REPO/pulls/$PR" --jq '.body // ""')"
summary="$(printf '%s\n' "$pr_body" | awk 'f && NF {print; n++} /^## Summary/ {f=1} n==3 {exit}')"
[ -z "$summary" ] && summary="$(printf '%s\n' "$pr_body" | awk 'NF && !/^#/ {print; n++} n==3 {exit}')"
run_url="https://github.com/$REPO/actions/runs/$GITHUB_RUN_ID"
{
  echo "<!-- screenshots-bot -->"
  echo "### Summary"
  printf '%s\n' "$summary"
  echo
  echo "### Screens changed vs main (${#changed[@]} of ${#files[@]})"
  if [ ${#changed[@]} -eq 0 ]; then
    echo "No screen changes."
  else
    n=0
    for name in "${changed[@]}"; do
      n=$((n+1)); [ $n -gt 24 ] && { echo "…and $(( ${#changed[@]} - 24 )) more (see the artifact)."; break; }
      echo "**${name%.png}**"
      echo
      echo "![${name%.png}](https://github.com/$REPO/blob/screenshots/$DEST/$name?raw=true)"
      echo
    done
  fi
  echo "<sub>All screenshots: [run artifacts]($run_url) · commit ${GITHUB_SHA:0:7}</sub>"
} > "$body_file"

existing="$(gh api "repos/$REPO/issues/$PR/comments" --paginate --jq '.[] | select(.body | startswith("<!-- screenshots-bot -->")) | .id' | head -1)"
if [ -n "$existing" ]; then
  gh api -X PATCH "repos/$REPO/issues/comments/$existing" -F body=@"$body_file" >/dev/null
else
  gh api "repos/$REPO/issues/$PR/comments" -F body=@"$body_file" >/dev/null
fi
echo "Posted screenshots comment (${#changed[@]} changed)."
