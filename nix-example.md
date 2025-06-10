# Using brepl with Nix

## Using in home.nix (Home Manager)

Add brepl to your `home.nix` configuration:

```nix
{ config, pkgs, ... }:

let
  brepl = pkgs.callPackage (pkgs.fetchFromGitHub {
    owner = "licht1stein";
    repo = "brepl";
    rev = "v1.0.0"; # or a specific commit hash
    sha256 = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="; # nix will tell you the correct hash
  } + "/package.nix") {};
in
{
  home.packages = [ brepl ];
}
```

## Using in a Flake

Create a `flake.nix` in your project:

```nix
{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    brepl = {
      url = "github:licht1stein/brepl";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, brepl }: {
    # Your flake outputs here
  };
}
```

## Direct Installation

```bash
# Build and install to user profile
nix-build -E 'with import <nixpkgs> {}; callPackage (fetchFromGitHub { owner = "licht1stein"; repo = "brepl"; rev = "v1.0.0"; sha256 = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="; } + "/package.nix") {}'
nix-env -i ./result

# Or run directly without installing
nix run -f '<nixpkgs>' --expr 'callPackage (fetchFromGitHub { owner = "licht1stein"; repo = "brepl"; rev = "v1.0.0"; sha256 = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="; } + "/package.nix") {}' -c brepl --version
```

## Getting the SHA256

When you first try to build, Nix will fail with an error showing the correct sha256. Copy that value and replace the placeholder.