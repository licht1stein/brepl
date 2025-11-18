{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  buildInputs = with pkgs; [
    babashka
  ];

  shellHook = ''
    echo "brepl development environment"
    echo "- babashka: $(bb --version)"
  '';
}
