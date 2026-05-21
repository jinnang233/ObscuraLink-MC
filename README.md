# ObscuraLink

> [!WARNING]
> This codebase was generated with AI assistance. Review the implementation carefully, especially the cryptography, key storage, networking behavior, and dependency configuration, before using it in any real environment.

ObscuraLink is a Fabric client mod that transports post-quantum encrypted chat packets through ordinary Minecraft chat. It uses compact binary packets, Base64URL transport encoding, automatic fragmentation, TOFU public-key storage, and authenticated AEAD encryption.

## Features

- Client-side `/enc` command tree.
- Classic McEliece/CMCE KEM key pairs generated automatically.
- Falcon signing key pairs generated automatically.
- AES-256-GCM with random 96-bit nonce per message.
- HKDF-SHA256 derives AEAD keys from KEM shared secrets.
- Optional signature verification for signed packets.
- Public-key import/export with Trust On First Use checks.
- Automatic chat fragmentation and out-of-order reassembly.
- Timeout cleanup and bounded receive caches.
- Cloth AutoConfig-backed configuration.

## Supported Versions

This first implementation targets:

- Minecraft Java `1.21.8`
- Fabric Loader `0.16.14`
- Fabric API `0.134.0+1.21.8`
- Loom `1.11-SNAPSHOT`
- Java `21`

Compatibility Notes: Minecraft/Fabric 26.1+ introduced larger mapping and toolchain changes. ObscuraLink keeps its protocol and crypto layers independent from Minecraft APIs so a future 26.x port is mostly limited to the client entrypoint, command, and chat-event adapters.

## Build

```bash
gradle build
```

If you prefer a wrapper, generate one with a local Gradle install:

```bash
gradle wrapper
./gradlew build
```

## Releases

GitHub Actions builds the mod and publishes release artifacts automatically when a tag matching `v*` is pushed:

```bash
git tag v0.4.2
git push origin v0.4.2
```

The release workflow can also be triggered manually from the Actions tab. Manual builds are published as prereleases.

## License

ObscuraLink is licensed under the BSD Zero Clause License (`0BSD`), a very permissive license with no attribution requirement.

## Run Client

```bash
gradle runClient
```

## Install

Build the project, then copy `build/libs/obscuralink-<version>.jar` into the client `mods` directory together with Fabric API. Cloth Config is included in the mod jar.

## Key Storage

ObscuraLink stores data under:

```text
config/obscuralink/
  keys/
    private/local.json
    public/*.json
  sessions/
  cache/
```

Private and public key material are stored separately. Public-key records include algorithm, owner, UUID, fingerprint, creation time, and Base64URL key data.

## Commands

```text
/enc tell <receiver> <message>
/enc stell <receiver> <message>
/enc exchange <receiver>
/enc etell <receiver> <message>
/enc showalgs
/enc key list
/enc key fingerprint <player>
/enc key export
/enc key import <player> <data-or-file>
```

Import flow:

1. The other player runs `/enc key export`.
2. They send you the exported Base64URL blob through a trusted side channel.
3. You run `/enc key import <player> <blob>`.
4. First import is trusted automatically. If a key changes later, ObscuraLink refuses to overwrite it silently.

## Protocol Format

Packets are compact binary and then Base64URL encoded for chat transport. The binary packet layout is:

```text
u8   protocolVersion
u8   packetType
u8   flags
u16  senderLength
bytes senderUtf8
u16  receiverLength
bytes receiverUtf8
i64  timestampMillis
16   messageId
i16  aadFragmentIndex
i16  aadFragmentTotal
u16  kemAlgorithmLength
bytes kemAlgorithmUtf8
u16  signatureAlgorithmLength
bytes signatureAlgorithmUtf8
u16  aeadAlgorithmLength
bytes aeadAlgorithmUtf8
u16  hkdfAlgorithmLength
bytes hkdfAlgorithmUtf8
u16  nonceLength
bytes nonce
i32  kemCiphertextLength
bytes kemCiphertext
i32  ciphertextLength
bytes ciphertext
i32  signatureLength
bytes signature
```

Packet types:

- `1`: KEM encrypted message.
- `2`: signed KEM encrypted message.
- `3`: session exchange.
- `4`: session message.

## Security Design

- AES/ECB is not used.
- AES-GCM is used with a fresh random nonce per encrypted message.
- `SecureRandom` generates message IDs, nonces, KEM randomness, and session material.
- KEM shared secrets are never used directly as AES keys.
- HKDF-SHA256 derives AEAD keys with the message ID as salt.
- AEAD AAD covers protocol version, packet type, flags, sender, receiver, message ID, packet-level fragment fields, and algorithm identifiers.
- Signatures cover AAD plus timestamp, nonce, KEM encapsulation, and ciphertext.
- Decryption rejects wrong receivers before attempting plaintext display.
- Decryption failures do not display garbage plaintext.
- Signature failures are displayed explicitly as invalid.

## Fragment Design

Each chat fragment has this form:

```text
[OBSCURA] <messageIdHex> <index> <total> <payload>
```

The receiver supports out-of-order fragments, ignores duplicate fragments, cleans up timed-out partial messages, caps pending messages, and rejects excessive fragment counts.

## Session Design

`/enc exchange` creates and persists local session material, then sends the session data inside a signed encrypted KEM envelope. `/enc etell` currently uses the same signed KEM envelope while keeping the session API and storage in place. The protocol already reserves packet types for direct PSK session packets.

## Known Limitations

- This is a client-only chat transport. Server chat filtering, signing, rate limits, and antispam plugins may interfere with large encrypted payloads.
- Classic McEliece public keys and encapsulations are large, so fragmentation is expected.
- Direct PSK-only session packets are reserved but not yet enabled as the default sending path.
- Public-key authenticity is TOFU-based; verify fingerprints out of band for stronger protection.

## Tests

Implemented test coverage:

- packet encode/decode
- encrypt/decrypt
- sign/verify
- fragment/reassemble
- timeout cleanup
- wrong receiver rejection
- modified ciphertext rejection
