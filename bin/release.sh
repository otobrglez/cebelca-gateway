#!/usr/bin/env bash
#
# Release the gateway to production, versioned automatically by git commit.
#
#   VERSION = git describe --tags --always --dirty
#     - no tags yet  -> abbreviated commit SHA (e.g. 5ac58e6)
#     - tags present -> nearest tag, with -N-g<sha> appended between tags
#
# Steps: build+push the image (tagged :latest AND :$VERSION), pin that exact
# version into k8s/base/kustomization.yaml, apply, and wait for the rollout.
# Pinning the commit keeps the deploy auditable and rollback-friendly: the
# committed kustomization.yaml always shows which commit is live.
#
# Usage:
#   bin/release.sh              # release current commit (refuses a dirty tree)
#   ALLOW_DIRTY=1 bin/release.sh  # allow a -dirty version (not reproducible)
set -euo pipefail

REPO="registry.folk-decibel.ts.net/cebelca/gateway"
NAMESPACE="cebelca-prod"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# A release should be reproducible: refuse a dirty tree unless explicitly allowed.
if [[ -n "$(git status --porcelain)" && "${ALLOW_DIRTY:-0}" != "1" ]]; then
  echo "error: working tree is dirty. Commit your changes, or set ALLOW_DIRTY=1 to release a -dirty build." >&2
  git status --short >&2
  exit 1
fi

VERSION="$(git describe --tags --always --dirty)"
echo "==> Releasing $REPO:$VERSION"

echo "==> Building + pushing image (tags :latest and :$VERSION)"
./mill gateway.docker.push

echo "==> Pinning kustomize image to :$VERSION"
(cd k8s/base && kustomize edit set image "$REPO=:$VERSION")

echo "==> Applying to $NAMESPACE"
kubectl apply -k k8s/base/

echo "==> Waiting for rollout"
kubectl rollout status deployment/gateway -n "$NAMESPACE" --timeout=120s

echo "==> Released $REPO:$VERSION"
echo "    Commit the kustomization.yaml change to record the live version:"
echo "      git add k8s/base/kustomization.yaml && git commit -m \"Deploy $VERSION\""
