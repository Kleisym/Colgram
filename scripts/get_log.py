import urllib.request
import json
import os
import sys

TOKEN = os.environ.get('GITHUB_TOKEN') or os.environ.get('GH_TOKEN')
if not TOKEN:
    sys.exit('GITHUB_TOKEN is not set. Export a fine-grained PAT with Actions:read before running.')

class NoAuthRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new_req = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new_req and 'Authorization' in new_req.headers:
            del new_req.headers['Authorization']
        return new_req

opener = urllib.request.build_opener(NoAuthRedirectHandler())

run_id = sys.argv[1] if len(sys.argv) > 1 else '35460858460'
url = f'https://api.github.com/repos/Kleisym/Colgram/actions/runs/{run_id}/jobs'
req = urllib.request.Request(url, headers={'Authorization': f'Bearer {TOKEN}', 'User-Agent': 'Colgram'})
with opener.open(req) as resp:
    jobs = json.loads(resp.read().decode('utf-8'))

for j in jobs.get('jobs', []):
    job_id = j['id']
    print(f"Job ID: {job_id} [{j['name']}] -> {j['conclusion']}")
    log_url = f"https://api.github.com/repos/Kleisym/Colgram/actions/jobs/{job_id}/logs"
    req_log = urllib.request.Request(log_url, headers={'Authorization': f'Bearer {TOKEN}', 'User-Agent': 'Colgram'})
    try:
        with opener.open(req_log) as lresp:
            log_text = lresp.read().decode('utf-8', errors='ignore')
            lines = log_text.splitlines()
            print(f"Total lines: {len(lines)}")
            with open('scripts/build_53_fail.log', 'w', encoding='utf-8') as lf:
                lf.write(log_text)
            errors = []
            for i, line in enumerate(lines):
                if "error:" in line.lower() or ": error" in line.lower() or "what went wrong:" in line.lower() or "failed with an exception" in line.lower():
                    start = max(0, i - 1)
                    end = min(len(lines), i + 6)
                    errors.append("\n".join(lines[start:end]))
            if errors:
                print("--- Errors found ---")
                for err in errors[:15]:
                    print(err)
                    print("="*40)
            else:
                print("--- Tail lines ---")
                for line in lines[-50:]:
                    print(line)
    except Exception as e:
        print("Failed to fetch log:", e)
