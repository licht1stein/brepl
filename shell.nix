{ pkgs ? import <nixpkgs> {} }:

let
  brepl = pkgs.callPackage ./package.nix {};
in
pkgs.mkShell {
  buildInputs = [ brepl ];
  
  shellHook = ''
    echo "brepl is now available in your shell"
    echo "Try: brepl --version"
  '';
}