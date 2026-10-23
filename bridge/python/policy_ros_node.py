#!/usr/bin/env python3
"""Host-side policy node: local checkpoint or remote HTTP model ↔ rosbridge."""

from __future__ import annotations

import argparse
import json
import logging
import time

logging.basicConfig(level=logging.INFO, format="[policy-node] %(message)s")
log = logging.getLogger("policy")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rosbridge", default="ws://127.0.0.1:9090")
    parser.add_argument("--source", choices=["local", "remote"], default="local")
    parser.add_argument("--path", default="", help="local pretrained_model dir or Hub id")
    parser.add_argument("--url", default="", help="remote inference HTTP endpoint")
    args = parser.parse_args()

    log.info("Policy node source=%s path=%s url=%s", args.source, args.path, args.url)
    if args.source == "local" and args.path:
        log.info("TODO: load PreTrainedPolicy.from_pretrained(%s)", args.path)
    elif args.source == "remote" and args.url:
        log.info("TODO: POST observations to %s", args.url)
    else:
        log.warning("No model configured yet — publishing readiness only")

    while True:
        log.info("policy node alive (%s)", args.source)
        time.sleep(5)


if __name__ == "__main__":
    main()
