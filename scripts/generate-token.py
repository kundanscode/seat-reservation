#!/usr/bin/env python3
"""
Generate a signed HS256 JWT for local testing with Postman or curl.
Usage:
    export JWT_SECRET='replace-with-a-long-random-local-development-secret'
    python3 scripts/generate-token.py --sub user-101 --scope user
    python3 scripts/generate-token.py --sub admin-1 --scope admin
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time

def base64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode('utf-8').rstrip('=')

def generate_token(secret: str, subject: str, scope: str, issuer: str = "seat-reservation", expiry_hours: int = 24) -> str:
    now = int(time.time())
    exp = now + (expiry_hours * 3600)

    header = {
        "alg": "HS256",
        "typ": "JWT"
    }
    payload = {
        "iss": issuer,
        "sub": subject,
        "scope": scope,
        "iat": now,
        "exp": exp
    }

    encoded_header = base64url_encode(json.dumps(header, separators=(',', ':')).encode('utf-8'))
    encoded_payload = base64url_encode(json.dumps(payload, separators=(',', ':')).encode('utf-8'))

    signing_input = f"{encoded_header}.{encoded_payload}".encode('utf-8')
    signature = hmac.new(secret.encode('utf-8'), signing_input, hashlib.sha256).digest()
    encoded_signature = base64url_encode(signature)

    return f"{encoded_header}.{encoded_payload}.{encoded_signature}"

def main():
    parser = argparse.ArgumentParser(description="Generate HS256 test JWT")
    parser.add_argument("--sub", default="user-101", help="Subject / user ID (default: user-101)")
    parser.add_argument("--scope", default="user", help="Scope claim ('user' or 'admin', default: user)")
    parser.add_argument("--iss", default="seat-reservation", help="Issuer claim (default: seat-reservation)")
    parser.add_argument("--secret", default=None, help="JWT secret (defaults to JWT_SECRET env var)")
    args = parser.parse_args()

    secret = args.secret or os.environ.get("JWT_SECRET")
    if not secret:
        print("Error: JWT_SECRET environment variable not set (or pass --secret <secret>)", file=sys.stderr)
        sys.exit(1)

    token = generate_token(secret, args.sub, args.scope, args.iss)
    print(token)

if __name__ == "__main__":
    main()
