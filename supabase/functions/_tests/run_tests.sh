#!/bin/bash

# Test runner for Court Lists API backend
#
# Usage:
#   ./run_tests.sh              # Run all tests (unit + integration if token set)
#   ./run_tests.sh unit         # Run only unit tests
#   ./run_tests.sh integration  # Run only integration tests (requires GOOGLE_ID_TOKEN)
#
# Environment variables:
#   GOOGLE_ID_TOKEN - Required for integration tests. Get from app logs/network inspector.
#   SUPABASE_FUNCTIONS_URL - Override base URL (default: production Supabase)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "======================================"
echo "Court Lists API - Test Suite"
echo "======================================"
echo ""

run_unit_tests() {
    echo -e "${GREEN}Running unit tests...${NC}"
    echo ""
    deno test --allow-read unit/
    echo ""
}

run_integration_tests() {
    if [ -z "$GOOGLE_ID_TOKEN" ]; then
        echo -e "${YELLOW}⚠️  GOOGLE_ID_TOKEN not set - skipping integration tests${NC}"
        echo "   Set GOOGLE_ID_TOKEN to run integration tests"
        echo ""
        return 0
    fi

    echo -e "${GREEN}Running integration tests...${NC}"
    echo ""
    deno test --allow-net --allow-env integration/
    echo ""
}

case "${1:-all}" in
    unit)
        run_unit_tests
        ;;
    integration)
        run_integration_tests
        ;;
    all)
        run_unit_tests
        run_integration_tests
        ;;
    *)
        echo "Usage: $0 [unit|integration|all]"
        exit 1
        ;;
esac

echo -e "${GREEN}======================================"
echo "Tests completed!"
echo -e "======================================${NC}"
