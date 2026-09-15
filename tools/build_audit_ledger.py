#!/usr/bin/env python3
"""Expand every original audit entry without treating fuzzy matches as resolved defects."""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

EXPECTED_SHA256 = 'ea13c84f0916586e30554b06b1f93db3a66c1aaa5ffd60a415fbf1ac1cee0c0c'
BASE_COMMIT = '0389bc227d8911c141ed6dd530d25316f58b79f0'
PATCHED_SCOPES = {
    'LENS-001': 'Camera-only foreground startup when location permission is denied; caught promotion failures. Android lifecycle validation still required.',
    'LENS-008': 'Non-sticky service and null-intent shutdown; not full scanning-session restoration.',
    'LENS-019': 'Paused settle/dwell accounting, preserved pre-start pause, current tick identity, callback shutdown.',
    'LENS-027': 'Country-aware OCR repair and non-speculative manual normalization. Existing stored identifiers are not migrated.',
    'LENS-028': 'Reject corrected signage before generic fallback.',
    'LENS-105': 'TTS_SERVICE package-visibility query. Physical TTS execution remains untested.',
    'LENS-124': 'SessionOptions ownership, fallback cleanup, constructor cleanup, idempotent close and inference/close exclusion. Activity handoff leaks remain separate.',
    'LENS-125': 'Content-hashed atomic model cache publication and interrupted-copy preservation.',
    'LENS-147': 'gradlew Git mode 100755, original script blob unchanged.',
}

def build_ledger(source: Path) -> dict:
    data = source.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest != EXPECTED_SHA256:
        raise ValueError('This mapping applies only to the audited XML fingerprint; revalidate IDs before using a different audit.')
    root = ET.fromstring(data)
    entries = []
    for group, expected in (('issues', 152), ('supplementalFindings', 326)):
        parent = root.find(group)
        if parent is None or len(parent) != expected:
            raise ValueError(f'Unexpected {group} count')
        for element in parent:
            inner = element if group == 'issues' else next(iter(element), element)
            identifier = element.get('id') or element.get('unifiedId')
            if not identifier:
                raise ValueError('Audit entry without an ID')
            title = inner.find('title')
            text = ' '.join(''.join(title.itertext()).split()) if title is not None else ''
            scope = PATCHED_SCOPES.get(identifier)
            entries.append({
                'id': identifier,
                'group': group,
                'title': text,
                'priority': inner.get('priority') or inner.get('severity'),
                'original_attributes': dict(element.attrib),
                'original_finding_attributes': dict(inner.attrib),
                'locations': [dict(node.attrib) for node in inner.findall('.//location')],
                'remediation_status': 'patched_scope_pending_integration' if scope else 'not_independently_revalidated',
                'remediation_scope': scope,
            })
    if len(entries) != 478 or len({item['id'] for item in entries}) != 478:
        raise ValueError('Audit entries were lost or duplicated')
    if set(PATCHED_SCOPES) - {item['id'] for item in entries}:
        raise ValueError('Remediation refers to missing audit IDs')
    return {
        'schema_version': 1,
        'source_sha256': digest,
        'baseline_commit': BASE_COMMIT,
        'code_commit': '07d8dc0689ce6be008a7ed73ef565083552e28a3',
        'counts': dict(Counter(item['remediation_status'] for item in entries)),
        'entry_count_is_not_unique_bug_count': True,
        'supplements_are_not_auto_closed_by_fuzzy_canonical_matches': True,
        'complete_repository_review': False,
        'entries': entries,
    }

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    try:
        ledger = build_ledger(args.source)
        args.output.write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    except (OSError, ValueError, ET.ParseError) as error:
        parser.exit(1, f'Cannot produce audit ledger: {error}\n')
    print(json.dumps({'entries': len(ledger['entries']), **ledger['counts']}))

if __name__ == '__main__':
    main()
