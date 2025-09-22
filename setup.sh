#!/usr/bin/env bash
# Crucible global setup script

# Detect where to install the wrapper
if [[ ":$PATH:" == *":$HOME/bin:"* ]] && mkdir -p "$HOME/bin" 2>/dev/null; then
	INSTALL_DIR="$HOME/bin"
elif [[ ":$PATH:" == *":$HOME/.local/bin:"* ]] && mkdir -p "$HOME/.local/bin" 2>/dev/null; then
	INSTALL_DIR="$HOME/.local/bin"
else
	echo "Error: No writable directory found in PATH"
	echo "Common locations to add to PATH:"
	echo "  - $HOME/bin"
	echo "  - $HOME/.local/bin"
	echo "Please create one of these directories and add it to your PATH"
	exit 1
fi

# Get the absolute path to crucible directory
CRUCIBLE_DIR="$(cd "$(dirname "$0")" && pwd)"

# Create the wrapper script
cat >"$INSTALL_DIR/c" <<EOF
#!/usr/bin/env bash
# Capture user's current directory before changing
export CRUCIBLE_USER_DIR="\$PWD"
cd "$CRUCIBLE_DIR" && bb crucible "\$@"
EOF

chmod +x "$INSTALL_DIR/c"
echo "✓ Crucible wrapper installed to $INSTALL_DIR/c"
echo "✓ You can now use 'c' from anywhere"
