	#!/bin/bash

# ===================== 基础配置 =====================

GITEE_URL="https://gitee.com/yunox/yunoTV.git"
GITHUB_URL="git@github.com:Yun0x/YunOTV.git"

GITEE_REMOTE="gitee"
GITHUB_REMOTE="github"

DEFAULT_BRANCH="master"
PUSH_TAGS=0
AUTO_INIT=1

# ===================== 初始化 =====================

echo ""
echo "========================================"
echo " Git 双平台一键提交与推送"
echo "========================================"
echo "当前目录：$(pwd)"
echo ""

# 检查 Git
if ! command -v git >/dev/null 2>&1; then
    echo "[ERROR] 未安装 Git"
    exit 1
fi

# 检查 Git 仓库
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    if [ "$AUTO_INIT" = "1" ]; then
        echo "[INFO] 初始化 Git 仓库..."
        git init || exit 1
    else
        echo "[ERROR] 当前目录不是 Git 仓库"
        exit 1
    fi
fi

# ===================== 检查分支 =====================

CURRENT_BRANCH=$(git symbolic-ref --short HEAD 2>/dev/null)

if [ -z "$CURRENT_BRANCH" ]; then
    echo "[INFO] 创建默认分支：$DEFAULT_BRANCH"
    git checkout -B "$DEFAULT_BRANCH" || exit 1
    CURRENT_BRANCH="$DEFAULT_BRANCH"
fi

echo "[INFO] 当前分支：$CURRENT_BRANCH"

# ===================== 配置远程仓库 =====================

setup_remote() {
    local name="$1"
    local url="$2"

    if git remote get-url "$name" >/dev/null 2>&1; then
        git remote set-url "$name" "$url" || return 1
        echo "[OK] 更新远程仓库：$name"
    else
        git remote add "$name" "$url" || return 1
        echo "[OK] 添加远程仓库：$name"
    fi
}

setup_remote "$GITEE_REMOTE" "$GITEE_URL" || exit 1
setup_remote "$GITHUB_REMOTE" "$GITHUB_URL" || exit 1

echo ""
git remote -v

# ===================== Git Ignore =====================

if [ ! -f ".gitignore" ]; then
    cat > .gitignore <<'IGNORE'
# Dependencies
node_modules/

# Build
dist/
target/

# Logs
logs/
*.log

# IDE
.idea/
.vscode/
*.iml

# System
.DS_Store
Thumbs.db

# Sensitive
runtime.core
runtime.window
runtime.state
private_pkcs8.pem
*.pem

# Environment
.env.local
.env.*.local
IGNORE

    echo "[OK] 已创建 .gitignore"
fi

# ===================== 检查变更 =====================

echo ""
echo "========================================"
echo " 当前 Git 变更"
echo "========================================"

git status --short

echo ""
echo "[安全提醒]"
echo "请确认没有提交密码、密钥及其他敏感文件。"
echo "已被 Git 跟踪的文件不会因加入 .gitignore 而自动忽略。"
echo ""

read -r -p "确认提交并推送到两个平台？(y/N): " CONFIRM

if [[ "$CONFIRM" != "y" && "$CONFIRM" != "Y" ]]; then
    echo "[INFO] 已取消操作"
    exit 0
fi

# ===================== Git Commit =====================

echo ""
echo "[INFO] 正在添加文件..."

git add . || exit 1

if ! git diff --cached --quiet; then

    COMMIT_MSG="${1:-}"

    if [ -z "$COMMIT_MSG" ]; then
        read -r -p "请输入提交说明： " COMMIT_MSG
    fi

    if [ -z "$COMMIT_MSG" ]; then
        COMMIT_MSG="update project"
    fi

    echo "[INFO] 提交说明：$COMMIT_MSG"

    git commit -m "$COMMIT_MSG" || exit 1

else
    echo "[INFO] 没有新的变更，跳过 Commit"
fi

# ===================== 推送 Gitee =====================

echo ""
echo "========================================"
echo " 正在推送到 Gitee..."
echo "========================================"

GITEE_RESULT=0

git push -u "$GITEE_REMOTE" "$CURRENT_BRANCH" || GITEE_RESULT=1

if [ "$PUSH_TAGS" = "1" ]; then
    git push "$GITEE_REMOTE" --tags || GITEE_RESULT=1
fi

# ===================== 推送 GitHub =====================

echo ""
echo "========================================"
echo " 正在推送到 GitHub..."
echo "========================================"

GITHUB_RESULT=0

git push -u "$GITHUB_REMOTE" "$CURRENT_BRANCH" || GITHUB_RESULT=1

if [ "$PUSH_TAGS" = "1" ]; then
    git push "$GITHUB_REMOTE" --tags || GITHUB_RESULT=1
fi

# ===================== 推送结果 =====================

echo ""
echo "========================================"
echo " 推送结果"
echo "========================================"

if [ "$GITEE_RESULT" = "0" ]; then
    echo "[SUCCESS] Gitee 推送成功"
else
    echo "[FAILED] Gitee 推送失败"
fi

if [ "$GITHUB_RESULT" = "0" ]; then
    echo "[SUCCESS] GitHub 推送成功"
else
    echo "[FAILED] GitHub 推送失败"
fi

echo ""
echo "当前分支：$CURRENT_BRANCH"
echo "Gitee ：$GITEE_URL"
echo "GitHub：$GITHUB_URL"
echo ""

if [ "$GITEE_RESULT" != "0" ] || [ "$GITHUB_RESULT" != "0" ]; then
    echo "[ERROR] 存在推送失败的平台"
    exit 1
fi

echo "[SUCCESS] 全部推送完成！"
exit 0

