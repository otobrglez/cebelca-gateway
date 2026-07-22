#!/usr/bin/env bash
#
# Release the gateway to production, versioned automatically by git commit.
#
#   VERSION = git describe --tags --always --dirty
#     - no tags yet  -> abbreviated commit SHA (e.g. 5ac58e6)
#     - tags present -> nearest tag, with -N-g<sha> appended between tags
#
# Design (bulletproof by construction):
#   * SINGLE source of truth: VERSION is computed ONCE here and handed to the
#     image build via IMAGE_VERSION, so `mill docker.push` and the kustomize
#     pin can never disagree (the drift that caused the earlier ImagePullBackOff).
#   * The working tree is ALWAYS left clean: an EXIT trap reverts the
#     kustomization.yaml edit if anything fails; on success the pin is committed
#     so git records exactly which commit is live (auditable, rollback-friendly).
#
# Usage:
#   bin/release.sh                # release current commit (refuses a dirty tree)
#   ALLOW_DIRTY=1 bin/release.sh  # allow a -dirty version (not reproducible)
#   NO_COMMIT=1   bin/release.sh  # apply the pin but don't commit it (tree left dirty)
set -euo pipefail

REPO="registry.folk-decibel.ts.net/cebelca/gateway"
NAMESPACE="cebelca-prod"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KUSTOMIZATION="k8s/base/kustomization.yaml"
cd "$ROOT"

# A release should be reproducible: refuse a dirty tree unless explicitly allowed.
if [[ -n "$(git status --porcelain)" && "${ALLOW_DIRTY:-0}" != "1" ]]; then
  echo "error: working tree is dirty. Commit your changes, or set ALLOW_DIRTY=1 to release a -dirty build." >&2
  git status --short >&2
  exit 1
fi

# --- single source of truth: compute the version exactly once ---
VERSION="$(git describe --tags --always --dirty)"
echo "==> Releasing $REPO:$VERSION"

# --- guarantee a clean tree no matter how we exit ---
# Until we've committed the pin, any exit (error, Ctrl-C, timeout) reverts the
# kustomization.yaml edit so the release never "leaves it changed".
PINNED=0
COMMITTED=0
cleanup() {
  if [[ "$PINNED" == "1" && "$COMMITTED" == "0" && "${NO_COMMIT:-0}" != "1" ]]; then
    echo "==> Reverting kustomization pin (release did not complete)"
    git checkout -- "$KUSTOMIZATION" 2>/dev/null || true
  fi
}
trap cleanup EXIT

echo "==> Building + pushing image (tags :latest and :$VERSION)"
# IMAGE_VERSION forces the build to use OUR version rather than recomputing git describe.
IMAGE_VERSION="$VERSION" ./mill gateway.docker.push

echo "==> Pinning kustomize image to :$VERSION"
(cd k8s/base && kustomize edit set image "$REPO=:$VERSION")
PINNED=1

echo "==> Applying to $NAMESPACE"
kubectl apply -k k8s/base/

echo "==> Waiting for rollout"
kubectl rollout status deployment/gateway -n "$NAMESPACE" --timeout=120s

# --- success: record the live version in git so the tree ends clean ---
if [[ "${NO_COMMIT:-0}" == "1" ]]; then
  echo "==> NO_COMMIT set: leaving $KUSTOMIZATION pinned to :$VERSION uncommitted"
elif git diff --quiet -- "$KUSTOMIZATION"; then
  echo "==> Already pinned to :$VERSION (no kustomization change to commit)"
  COMMITTED=1
else
  git commit -q -m "Deploy $VERSION" -- "$KUSTOMIZATION"
  COMMITTED=1
  echo "==> Committed pin: Deploy $VERSION"
fi

echo "==> Released $REPO:$VERSION"
