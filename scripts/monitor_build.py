import urllib.request
import json
import os
import sys
import time

token = os.environ.get('GITHUB_TOKEN') or os.environ.get('GH_TOKEN')
if not token:
    sys.exit('GITHUB_TOKEN is not set. Export a fine-grained PAT with Actions:read before running.')
headers = {
    'User-Agent': 'ColgramBot',
    'Accept': 'application/vnd.github.v3+json',
    'Authorization': f'Bearer {token}'
}

class NoAuthRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new_req = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new_req and 'Authorization' in new_req.headers:
            del new_req.headers['Authorization']
        return new_req

opener = urllib.request.build_opener(NoAuthRedirectHandler)

run_id = None
for _ in range(5):
    try:
        url = 'https://api.github.com/repos/Kleisym/Colgram/actions/runs'
        with opener.open(urllib.request.Request(url, headers=headers)) as resp:
            data = json.loads(resp.read().decode())
            runs = data.get('workflow_runs', [])
            if runs:
                run_id = runs[0]['id']
                print(f"Tracking newest run: {run_id} ({runs[0]['head_commit']['message']})")
                break
    except Exception:
        pass
    time.sleep(3)

if not run_id:
    print("No run found to track.")
    exit(0)

# Monitor loop
for i in range(60): # up to 15 minutes
    try:
        url = f'https://api.github.com/repos/Kleisym/Colgram/actions/runs/{run_id}'
        with opener.open(urllib.request.Request(url, headers=headers)) as resp:
            data = json.loads(resp.read().decode())
            status = data.get('status')
            conclusion = data.get('conclusion')
            print(f"[{i+1}/60] Run {run_id}: {status} / {conclusion}")
            
            # Check steps
            jobs_url = data.get('jobs_url')
            with opener.open(urllib.request.Request(jobs_url, headers=headers)) as jresp:
                jdata = json.loads(jresp.read().decode())
                for job in jdata.get('jobs', []):
                    for s in job.get('steps', []):
                        if s.get('status') == 'in_progress':
                            print(f"   -> [IN PROGRESS] {s['name']}")
                        elif s.get('conclusion') == 'failure':
                            print(f"   -> [FAILED] {s['name']}")

            if status == 'completed':
                if conclusion == 'failure':
                    log_url = f"https://api.github.com/repos/Kleisym/Colgram/actions/jobs/{jdata['jobs'][0]['id']}/logs"
                    with opener.open(urllib.request.Request(log_url, headers=headers)) as lresp:
                        log_content = lresp.read().decode('utf-8', errors='ignore')
                        with open("c:\\Colgram\\scripts\\error_log.txt", "w", encoding="utf-8") as lf:
                            lf.write(log_content)
                        print(f"Logged failure to error_log.txt ({len(log_content)} bytes)")
                        exit(1)
                elif conclusion == 'success':
                    print("SUCCESS! APK build and release completed!")
                    exit(0)
                break
    except Exception as e:
        print("Poll error:", e)
    time.sleep(15)
