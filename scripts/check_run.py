import urllib.request
import json
import os
import sys
import time

TOKEN = os.environ.get('GITHUB_TOKEN') or os.environ.get('GH_TOKEN')
if not TOKEN:
    sys.exit('GITHUB_TOKEN is not set. Export a fine-grained PAT with Actions:read before running.')
HEADERS = {'Authorization': f'Bearer {TOKEN}', 'User-Agent': 'Colgram'}

def get_runs():
    url = 'https://api.github.com/repos/Kleisym/Colgram/actions/runs?per_page=5'
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode('utf-8'))

def get_run(run_id):
    url = f'https://api.github.com/repos/Kleisym/Colgram/actions/runs/{run_id}'
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode('utf-8'))

def get_jobs(run_id):
    url = f'https://api.github.com/repos/Kleisym/Colgram/actions/runs/{run_id}/jobs'
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode('utf-8'))

def main():
    if len(sys.argv) > 1:
        run_id = sys.argv[1]
    else:
        runs = get_runs()
        for r in runs.get('workflow_runs', []):
            print(f"Run #{r.get('run_number')} (ID {r.get('id')}): {r.get('status')} [{r.get('conclusion')}] - {r.get('head_sha')[:7]} - {r.get('head_commit',{}).get('message','').splitlines()[0]}")
        if not runs.get('workflow_runs'):
            print("No runs found.")
            return
        run_id = str(runs['workflow_runs'][0]['id'])

    run_data = get_run(run_id)
    print(f"\n--- Run #{run_data.get('run_number')} (ID {run_id}): {run_data.get('status')} -> {run_data.get('conclusion')} ---")
    jobs_data = get_jobs(run_id)
    for job in jobs_data.get('jobs', []):
        print(f"  Job: {job.get('name')} [{job.get('status')}]")
        for step in job.get('steps', []):
            if step.get('status') == 'in_progress':
                print(f"    --> IN PROGRESS: {step.get('name')}")
            elif step.get('conclusion') == 'failure':
                print(f"    --> FAILED: {step.get('name')}")
            elif step.get('status') == 'completed':
                print(f"    [OK] {step.get('name')}")

if __name__ == '__main__':
    main()
