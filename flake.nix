{
  description = "Crucible - Clojure development environment with MCP integration";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/ae1aa6751ce85669124f7ad4fd5fd67cb91b52e4";
    mcp-servers-nix = {
      url = "github:natsukium/mcp-servers-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      mcp-servers-nix,
      flake-utils,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            # Clojure development
            babashka
            clojure
            openjdk21

            # Linting tools
            clj-kondo # Clojure/ClojureScript/EDN linter
            jq # JSON processor/validator
            nixfmt-tree # Nix formatter
            zprint # Clojure formatter

            # Development tools
            curl
            git
            which

            # Optional: direnv for automatic environment loading
            direnv
          ];

          shellHook =
            let
              config = mcp-servers-nix.lib.mkConfig pkgs {
                programs = {
                  playwright.enable = true;
                };
                settings.servers = {
                  clojure-mcp = {
                    command = "/bin/sh";
                    args = [
                      "-c"
                      "PORT=$(cat .nrepl-port); clojure -X:mcp :port $PORT"
                    ];

                  };
                };
              };
            in
            ''
              if [ -L ".mcp.json" ]; then
                unlink .mcp.json
              fi
              ln -sf ${config} .mcp.json

              # Set up npm cache directory
              export NPM_CONFIG_CACHE=$PWD/.npm-cache
              mkdir -p $NPM_CONFIG_CACHE
            '';
        };
      }
    );
}
