{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  buildInputs = with pkgs; [
    babashka
    parinfer-rust
  ];

  shellHook = ''
    echo "brepl development environment"
    echo "- babashka: $(bb --version)"
    echo "- parinfer-rust: available"
  '';
}
