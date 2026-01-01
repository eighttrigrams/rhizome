#!/bin/bash

echo "=== Tearing down Rhizome development instance ==="

# Kill backend (port 3006)
if lsof -ti:3006 > /dev/null 2>&1; then
    echo "Stopping backend on port 3006..."
    lsof -ti:3006 | xargs kill -9 2>/dev/null
else
    echo "No backend running on port 3006"
fi

# Kill shadow-cljs watch processes
if pgrep -f "shadow-cljs watch" > /dev/null 2>&1; then
    echo "Stopping shadow-cljs watch..."
    pkill -f "shadow-cljs watch"
else
    echo "No shadow-cljs watch running"
fi

echo "Done."
