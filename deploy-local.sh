#!/bin/bash
# Memegram K8s Local Development Script
# Usage: ./deploy-local.sh [up|down|status|build|logs]

set -e

PROFILE="memegram"
NAMESPACE="memegram"
OVERLAY="k8s/overlays/dev"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log()  { echo -e "${GREEN}[+]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
err()  { echo -e "${RED}[x]${NC} $1"; }

ensure_minikube() {
    if ! minikube status -p "$PROFILE" &>/dev/null; then
        log "Starting minikube cluster '$PROFILE'..."
        minikube start --driver=docker --cpus=4 --memory=7000 --disk-size=30g \
            --kubernetes-version=v1.31.0 --profile="$PROFILE"
    else
        log "Minikube '$PROFILE' is already running"
    fi
}

build_images() {
    log "Setting Docker env to minikube..."
    eval $(minikube -p "$PROFILE" docker-env)

    SERVICES=(auth-service user-service contacts-service messaging-service media-service item-storage-service notifications-service orchestrator)

    for svc in "${SERVICES[@]}"; do
        log "Building $svc..."
        docker build -t "memegram/$svc:latest" "$SCRIPT_DIR/backend/$svc" -q
    done
    log "All images built!"
}

deploy() {
    log "Deploying to Kubernetes..."
    kubectl apply -k "$SCRIPT_DIR/$OVERLAY"
    log "Waiting for pods to be ready..."
    kubectl wait --for=condition=ready pod --all -n "$NAMESPACE" --timeout=180s 2>/dev/null || true
    status
}

teardown() {
    warn "Tearing down all resources..."
    kubectl delete -k "$SCRIPT_DIR/$OVERLAY" --ignore-not-found
    log "Resources deleted"
}

status() {
    echo ""
    log "=== Pod Status ==="
    kubectl get pods -n "$NAMESPACE" -o wide
    echo ""
    log "=== Services ==="
    kubectl get svc -n "$NAMESPACE"
}

show_logs() {
    local svc="${2:-orchestrator}"
    kubectl logs -n "$NAMESPACE" -l "app=$svc" --tail=50 -f
}

port_forward() {
    log "Forwarding orchestrator to localhost:8000..."
    log "Press Ctrl+C to stop"
    kubectl port-forward -n "$NAMESPACE" svc/orchestrator 8000:8000
}

dashboard() {
    log "Opening Kubernetes dashboard..."
    minikube -p "$PROFILE" dashboard
}

case "${1}" in
    up)
        ensure_minikube
        build_images
        deploy
        ;;
    down)
        teardown
        ;;
    build)
        ensure_minikube
        build_images
        ;;
    deploy)
        deploy
        ;;
    status)
        status
        ;;
    logs)
        show_logs "$@"
        ;;
    forward)
        port_forward
        ;;
    dashboard)
        dashboard
        ;;
    *)
        echo "Usage: $0 {up|down|build|deploy|status|logs [service]|forward|dashboard}"
        echo ""
        echo "  up        - Start minikube, build images, deploy everything"
        echo "  down      - Delete all K8s resources"
        echo "  build     - Build Docker images in minikube"
        echo "  deploy    - Apply K8s manifests (no rebuild)"
        echo "  status    - Show pod and service status"
        echo "  logs      - Follow logs (default: orchestrator). Usage: $0 logs auth-service"
        echo "  forward   - Port-forward orchestrator to localhost:8000"
        echo "  dashboard - Open minikube web dashboard"
        ;;
esac
