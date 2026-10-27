"""Check resolved public Maven coordinates with OSV; never send ledger or source data."""
import argparse
import json
from pathlib import Path
import re
import urllib.request
from datetime import datetime, timezone


def request(url, payload=None):
    data = None if payload is None else json.dumps(payload).encode('utf-8')
    req = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json', 'User-Agent': 'Masroofi-dependency-audit'})
    with urllib.request.urlopen(req, timeout=45) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('dependency_report', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    text = args.dependency_report.read_text(encoding='utf-8-sig')
    pattern = r'(?:\+---|\\---) ([\w.-]+):([\w.-]+):([\w.+-]+)(?: -> ([\w.+-]+))?'
    coordinates = sorted({(group + ':' + name, resolved or version) for group, name, version, resolved in re.findall(pattern, text)})
    if not coordinates or 'BUILD SUCCESSFUL' not in text:
        raise SystemExit('A complete resolved Gradle dependency report is required.')
    queries = [{'package': {'ecosystem': 'Maven', 'name': name}, 'version': version} for name, version in coordinates]
    result = request('https://api.osv.dev/v1/querybatch', {'queries': queries})
    results = result.get('results', [])
    if len(results) != len(coordinates):
        raise SystemExit('Incomplete vulnerability-service response; audit cannot pass.')
    findings = []
    details = {}
    for (name, version), item in zip(coordinates, results):
        for vulnerability in item.get('vulns', []):
            identifier = vulnerability['id']
            if identifier not in details:
                details[identifier] = request('https://api.osv.dev/v1/vulns/' + identifier)
            detail = details[identifier]
            if detail.get('withdrawn'):
                continue
            findings.append({'package': name, 'version': version, 'id': identifier,
                'aliases': detail.get('aliases', []), 'summary': detail.get('summary', ''),
                'severity': detail.get('database_specific', {}).get('severity', 'unclassified'),
                'references': detail.get('references', []), 'affected': detail.get('affected', [])})
    report = {'checked_at_utc': datetime.now(timezone.utc).isoformat(), 'provider': 'https://osv.dev',
        'scope': 'Resolved Maven coordinates only; native C/C++ libraries and unknown vulnerabilities are not covered.',
        'package_count': len(coordinates), 'packages': [{'name': n, 'version': v} for n, v in coordinates],
        'findings': findings, 'passed': not findings}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'packages': len(coordinates), 'findings': [{'package': f['package'], 'version': f['version'], 'id': f['id'], 'severity': f['severity']} for f in findings], 'passed': report['passed']}))
    raise SystemExit(0 if report['passed'] else 1)


if __name__ == '__main__':
    main()
