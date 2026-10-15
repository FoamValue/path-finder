#!/usr/bin/env bash
# PR 完备性检查脚本
# Layer 1（警告）：双语文档配对，只改一侧时提示同步
# Layer 2（阻断）：发版 PR 校验版本文件是否同步更新
# 结果写入 $GITHUB_STEP_SUMMARY；仅 Layer 2 存在阻断项时 exit 1
set -uo pipefail

REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
PR_NUMBER="${PR_NUMBER:?PR_NUMBER is required}"
API="repos/${REPO}"
SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/stdout}"

PR_JSON="$(gh api "${API}/pulls/${PR_NUMBER}")"
PR_TITLE="$(printf '%s' "$PR_JSON" | jq -r '.title')"
BASE_SHA="$(printf '%s' "$PR_JSON" | jq -r '.base.sha')"
HEAD_SHA="$(printf '%s' "$PR_JSON" | jq -r '.head.sha')"

mapfile -t FILES < <(gh api --paginate "${API}/pulls/${PR_NUMBER}/files" --jq '.[].filename')

changed() {
  local f="$1"
  for e in "${FILES[@]}"; do
    [[ "$e" == "$f" ]] && return 0
  done
  return 1
}

file_has_pattern() { # file pattern → 0 含 / 1 不含（必须读 PR head 分支的文件，默认读 main 会误判）
  local file="$1" pattern="$2" content
  # 先解码存变量再 grep：避免 pipefail 下 grep 提前退出令 base64 收 SIGPIPE 污染管道退出码
  content="$(gh api "repos/${REPO}/contents/${file}?ref=${HEAD_SHA}" --jq '.content' 2>/dev/null | base64 -d 2>/dev/null)"
  printf '%s' "$content" | grep -Eq "$pattern"
}

echo "## PR 完备性检查" > "$SUMMARY"
BLOCK=0

########## Layer 1：双语文档配对（仅警告） ##########
echo "### 文档同步（警告，不阻断）" >> "$SUMMARY"
PAIRS=(
  "README.md|README.zh-CN.md"
  "CHANGELOG.md|CHANGELOG.zh-CN.md"
  "SECURITY.md|SECURITY.zh-CN.md"
)
WARN=0
for pair in "${PAIRS[@]}"; do
  en="${pair%%|*}"; zh="${pair##*|}"
  # 仅当对侧文件在 base 分支已存在时才提示（避免要求补齐尚不存在的历史文件）
  if changed "$en" && ! changed "$zh" \
     && gh api "repos/${REPO}/contents/${zh}?ref=${BASE_SHA}" --jq '.name' &>/dev/null; then
    echo "- ⚠️ 已修改 \`${en}\` 但未同步 \`${zh}\`，请评估是否需要同步" >> "$SUMMARY"
    WARN=1
  elif changed "$zh" && ! changed "$en" \
     && gh api "repos/${REPO}/contents/${en}?ref=${BASE_SHA}" --jq '.name' &>/dev/null; then
    echo "- ⚠️ 已修改 \`${zh}\` 但未同步 \`${en}\`，请评估是否需要同步" >> "$SUMMARY"
    WARN=1
  fi
done
[ "$WARN" -eq 0 ] && echo "- ✅ 无缺失的双语同步项" >> "$SUMMARY"

########## Layer 2：发版 PR 卫生（阻断） ##########
echo "### 发版卫生（仅发版 PR 检查，阻断）" >> "$SUMMARY"
if [[ "$PR_TITLE" =~ ^chore\(main\):\ release\ ([0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
  VERSION="${BASH_REMATCH[1]}"
  echo "识别为发版 PR，目标版本：\`$VERSION\`" >> "$SUMMARY"

  check() { # desc file pattern
    local desc="$1" file="$2" pattern="$3"
    if file_has_pattern "$file" "$pattern"; then
      echo "- ✅ ${desc}" >> "$SUMMARY"
    else
      echo "- ❌ ${desc}（\`${file}\` 未匹配 \`$pattern\`）" >> "$SUMMARY"
      BLOCK=1
    fi
  }

  check "CHANGELOG.md 存在 \`## [$VERSION]\` 条目" \
    "CHANGELOG.md" "^## \\[$VERSION\\]"
  check "server/pom.xml 项目版本已更新为 $VERSION" \
    "server/pom.xml" "<version>$VERSION</version>"
  check "frontend/package.json 版本已更新为 $VERSION" \
    "frontend/package.json" "\"version\": \"$VERSION\""
  check "frontend/package-lock.json 版本已更新为 $VERSION" \
    "frontend/package-lock.json" "\"version\": \"$VERSION\""
else
  echo "- 非发版 PR，跳过" >> "$SUMMARY"
fi

########## 结论 ##########
if [ "$BLOCK" -eq 1 ]; then
  echo "### ❌ 存在阻断项，禁止合并" >> "$SUMMARY"
  exit 1
fi
echo "### ✅ 通过" >> "$SUMMARY"
exit 0
