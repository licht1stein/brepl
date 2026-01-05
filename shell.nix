{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  buildInputs = with pkgs; [
    babashka
  ];

  shellHook = ''
    git config core.hooksPath .githooks
    echo "brepl development environment"
    echo "- babashka: $(bb --version)"
  '';
}
