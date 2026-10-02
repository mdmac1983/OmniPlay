#!/usr/bin/env python3
"""
Mint an OmniPlay activation key.

Activation keys are signed by the owner's private key (ECDSA P-256 / SHA-256). The private key is kept here only in
encrypted form (owner_material.json, AES-256-GCM, unlocked with the owner password). The app verifies keys against the
public key baked into Activation.kt — so keys can be minted on any machine with this script and the owner password, and
cannot be forged.

Usage:
    python3 mint_key.py --sub "Jane's phone"                 # never expires
    python3 mint_key.py --sub "Trial" --days 30              # expires in 30 days
"""
import argparse, base64, hashlib, json, os, sys, getpass, time
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives import serialization

HERE = os.path.dirname(os.path.abspath(__file__))

def b64u(b): return base64.urlsafe_b64encode(b).decode().rstrip("=")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sub", default="", help="who the key is for (shown in the app)")
    ap.add_argument("--days", type=int, default=0, help="expiry in days (0 = never)")
    a = ap.parse_args()
    m = json.load(open(os.path.join(HERE, "owner_material.json")))
    pw = getpass.getpass("Owner password: ").encode()
    key = hashlib.pbkdf2_hmac("sha256", pw, base64.b64decode(m["encSalt"]), m["iter"], 32)
    try:
        priv_der = AESGCM(key).decrypt(base64.b64decode(m["encIv"]), base64.b64decode(m["encKey"]), b"omniplay-owner-v1")
    except Exception:
        print("Wrong owner password."); sys.exit(1)
    priv = serialization.load_der_private_key(priv_der, password=None)
    payload = {"sub": a.sub}
    if a.days > 0: payload["exp"] = int(time.time()) + a.days * 86400
    pb = json.dumps(payload, separators=(",", ":")).encode()
    sig = priv.sign(pb, ec.ECDSA(hashlib.sha256().name and __import__("cryptography.hazmat.primitives.hashes", fromlist=["SHA256"]).SHA256()))
    print("\nActivation key:\n")
    print(f"{b64u(pb)}.{b64u(sig)}\n")

if __name__ == "__main__":
    main()
