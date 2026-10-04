#!/usr/bin/env python3
"""Check an archived JFR's digest and metadata counts against a fresh JDK summary."""
import argparse
import hashlib
import json
from pathlib import Path
import re


PRIVATE_EVENTS = {
    'jdk.InitialEnvironmentVariable', 'jdk.InitialSystemProperty',
    'jdk.InitialSecurityProperty', 'jdk.SystemProcess', 'jdk.ProcessStart', 'jdk.JVMInformation',
}
SAMPLED_EVENTS = {
    'jdk.ExecutionSample', 'jdk.ObjectAllocationSample', 'jdk.GarbageCollection', 'jdk.GCPhasePause',
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recording', type=Path, required=True)
    parser.add_argument('--summary', type=Path, required=True)
    parser.add_argument('--identity', type=Path, required=True)
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    identity = json.loads(args.identity.read_text(encoding='utf-8'))
    counts = {}
    for line in args.summary.read_text(encoding='utf-8').splitlines():
        match = re.fullmatch(r'\s+(jdk\.\w+)\s+(\d+)\s+\d+\s*', line)
        if match:
            event, count = match.groups()
            require(event not in counts, f'Duplicate summary event: {event}')
            counts[event] = int(count)
    require(identity['status'] == 'PASS', 'Unverified recording identity')
    require(set(identity['privateMetadataCounts']) == PRIVATE_EVENTS, 'Unexpected metadata event inventory')
    for event in PRIVATE_EVENTS:
        require(event in counts and counts[event] == 0, f'Metadata event must be absent: {event}')
        require(identity['privateMetadataCounts'][event] == counts[event], f'Metadata count mismatch: {event}')
    require(set(identity['selectedCounts']) == SAMPLED_EVENTS | {'jdk.FileWrite'}, 'Unexpected sample inventory')
    for event, recorded in identity['selectedCounts'].items():
        require(event in counts and counts[event] == recorded, f'Sampled count mismatch: {event}')
        if event in SAMPLED_EVENTS:
            require(recorded > 0, f'Missing diagnostic samples: {event}')
    recording = args.recording.read_bytes()
    require(len(recording) == identity['recordingBytes'], 'Recording byte-size mismatch')
    require(hashlib.sha256(recording).hexdigest() == identity['recordingSha256'], 'Recording digest mismatch')
    require(hashlib.sha256(args.source.read_bytes()).hexdigest() == identity['profileSourceSha256'],
            'Archived profiler source digest mismatch')
    print(json.dumps({'status': 'PASS', 'recordingSha256': identity['recordingSha256'],
                      'privateMetadataCounts': {event: counts[event] for event in sorted(PRIVATE_EVENTS)},
                      'selectedCounts': identity['selectedCounts'], 'engineExecuted': False}, indent=2))


if __name__ == '__main__':
    main()
